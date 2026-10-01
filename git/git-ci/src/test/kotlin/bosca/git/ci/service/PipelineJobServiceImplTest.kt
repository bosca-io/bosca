package bosca.git.ci.service

import bosca.git.ci.configuration.KubernetesCiDispatchConfiguration
import bosca.di.asProvider
import bosca.di.MissingObjectProvider
import bosca.di.annotation.InternalDI
import bosca.git.ci.repository.PipelineJobRepository
import bosca.git.ci.repository.PipelineRunRepository
import bosca.git.ci.repository.PipelineStepRepository
import bosca.git.model.JobDefinition
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineStep
import bosca.git.model.PipelineTriggerType
import bosca.git.model.StepDefinition
import bosca.git.service.LogLine
import bosca.git.service.LogStream
import bosca.git.service.PipelineLogService
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class PipelineJobServiceImplTest {

    private val jobRepository = mockk<PipelineJobRepository>(relaxed = true)
    private val stepRepository = mockk<PipelineStepRepository>(relaxed = true)
    private val runService = mockk<bosca.git.service.PipelineRunService>(relaxed = true)
    private val logService = mockk<PipelineLogService>(relaxed = true)
    private val kubernetesJobDispatchService = mockk<KubernetesJobDispatchService>(relaxed = true)
    private lateinit var service: PipelineJobServiceImpl

    private val runId = UUID.random()

    @BeforeTest
    fun setup() {
        coEvery { jobRepository.create(any()) } answers { (firstArg() as PipelineJob).copy(id = UUID.random()) }
        coEvery { stepRepository.create(any()) } answers { (firstArg() as PipelineStep).copy(id = UUID.random()) }
        coEvery { runService.findById(any()) } returns null
        bosca.di.provides<bosca.git.service.PipelineRunService>(singleton = true) { runService }
        coEvery { logService.getLogs(any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        service = PipelineJobServiceImpl(
            jobRepository,
            stepRepository,
            logService,
            kotlinx.serialization.json.Json,
            KubernetesCiDispatchConfiguration.disabled,
            kubernetesJobDispatchService.asProvider(),
        )
    }

    @Test
    fun `createJobs creates one job per definition`() = runTest {
        val definitions = mapOf(
            "build" to JobDefinition(
                runner = "linux",
                steps = listOf(StepDefinition(name = "Checkout", uses = "checkout"))
            ),
            "test" to JobDefinition(
                runner = "linux",
                needs = listOf("build"),
                steps = listOf(StepDefinition(name = "Test", run = "npm test"))
            )
        )

        val result = service.createJobs(runId, definitions)

        assertEquals(2, result.size)
        coVerify(exactly = 2) { jobRepository.create(any()) }
        coVerify(exactly = 2) { stepRepository.create(any()) }
    }

    @Test
    fun `createJobs expands matrix into multiple jobs`() = runTest {
        val definitions = mapOf(
            "test" to JobDefinition(
                runner = "linux",
                matrix = mapOf("java" to listOf("21", "25"), "os" to listOf("linux", "macos")),
                steps = listOf(StepDefinition(name = "Test", run = "test"))
            )
        )

        val result = service.createJobs(runId, definitions)

        assertEquals(4, result.size)
        coVerify(exactly = 4) { jobRepository.create(any()) }
        coVerify(exactly = 4) { stepRepository.create(any()) }
    }

    @Test
    fun `createJobs names matrix jobs with values`() = runTest {
        val captured = mutableListOf<PipelineJob>()
        coEvery { jobRepository.create(any()) } answers {
            val job = firstArg<PipelineJob>()
            captured.add(job)
            job.copy(id = UUID.random())
        }

        val definitions = mapOf(
            "test" to JobDefinition(
                matrix = mapOf("java" to listOf("21", "25")),
                steps = listOf(StepDefinition(name = "Test", run = "test"))
            )
        )

        service.createJobs(runId, definitions)

        assertEquals(2, captured.size)
        assertTrue(captured.any { it.name.contains("21") })
        assertTrue(captured.any { it.name.contains("25") })
    }

    @Test
    fun `createJobs preserves job dependencies`() = runTest {
        val captured = slot<PipelineJob>()
        coEvery { jobRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }

        val definitions = mapOf(
            "deploy" to JobDefinition(
                needs = listOf("build", "test"),
                steps = listOf(StepDefinition(name = "Deploy", run = "deploy"))
            )
        )

        service.createJobs(runId, definitions)
        assertEquals(listOf("build", "test"), captured.captured.dependsOn)
    }

    @Test
    fun `createJobs creates steps in ordinal order`() = runTest {
        val capturedSteps = mutableListOf<PipelineStep>()
        coEvery { stepRepository.create(any()) } answers {
            val step = firstArg<PipelineStep>()
            capturedSteps.add(step)
            step.copy(id = UUID.random())
        }

        val definitions = mapOf(
            "build" to JobDefinition(
                steps = listOf(
                    StepDefinition(name = "Checkout", uses = "checkout"),
                    StepDefinition(name = "Build", run = "./gradlew build"),
                    StepDefinition(name = "Upload", uses = "upload-artifact")
                )
            )
        )

        service.createJobs(runId, definitions)

        assertEquals(3, capturedSteps.size)
        assertEquals(0, capturedSteps[0].ordinal)
        assertEquals(1, capturedSteps[1].ordinal)
        assertEquals(2, capturedSteps[2].ordinal)
        assertEquals("Checkout", capturedSteps[0].name)
        assertEquals("Build", capturedSteps[1].name)
        assertEquals("Upload", capturedSteps[2].name)
    }

    @Test
    fun `createJobs sets runner label from definition`() = runTest {
        val captured = slot<PipelineJob>()
        coEvery { jobRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }

        val definitions = mapOf(
            "build" to JobDefinition(
                runner = "macos-arm64",
                steps = listOf(StepDefinition(name = "Build", run = "build"))
            )
        )

        service.createJobs(runId, definitions)
        assertEquals("macos-arm64", captured.captured.runnerLabel)
    }

    @Test
    fun `createJobs uses default runner when not specified`() = runTest {
        val captured = slot<PipelineJob>()
        coEvery { jobRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }

        val definitions = mapOf(
            "build" to JobDefinition(
                steps = listOf(StepDefinition(name = "Build", run = "build"))
            )
        )

        service.createJobs(runId, definitions)
        assertEquals("default", captured.captured.runnerLabel)
    }

    @Test
    fun `claimJob returns job when available`() = runTest {
        val agentId = UUID.random()
        val job = testJob()
        val claimedJob = job.copy(status = PipelineRunStatus.RUNNING, agentId = agentId)
        coEvery { jobRepository.findCurrentByAgent(agentId) } returns null
        coEvery { jobRepository.findNextAvailable(listOf("linux")) } returns job
        coEvery {
            jobRepository.claimJobById(job.id, agentId, null, PipelineRunStatus.RUNNING)
        } returns claimedJob

        val result = service.claimJob(agentId, listOf("linux"))

        assertNotNull(result)
        coVerify { jobRepository.claimJobById(job.id, agentId, null, PipelineRunStatus.RUNNING) }
    }

    @Test
    fun `claimJob returns null when no jobs available`() = runTest {
        val agentId = UUID.random()
        coEvery { jobRepository.findCurrentByAgent(agentId) } returns null
        coEvery { jobRepository.findNextAvailable(any()) } returns null

        val result = service.claimJob(agentId, listOf("linux"))
        assertNull(result)
    }

    @Test
    fun `claimJob excludes labels routed through kubernetes`() = runTest {
        val agentId = UUID.random()
        val configuredService = PipelineJobServiceImpl(
            jobRepository,
            stepRepository,
            logService,
            kotlinx.serialization.json.Json,
            KubernetesCiDispatchConfiguration(setOf("android")),
            mockk<KubernetesJobDispatchService>().asProvider(),
        )
        coEvery { jobRepository.findCurrentByAgent(agentId) } returns null
        coEvery { jobRepository.findNextAvailable(listOf("linux")) } returns null

        assertNull(configuredService.claimJob(agentId, listOf("android")))
        assertNull(configuredService.claimJob(agentId, listOf("android", "linux")))

        coVerify(exactly = 0) { jobRepository.findNextAvailable(listOf("android")) }
        coVerify(exactly = 1) { jobRepository.findNextAvailable(listOf("linux")) }
    }

    @Test
    fun `claimJob retains all labels when Kubernetes dispatch service is absent`() = runTest {
        val agentId = UUID.random()
        val configuredService = PipelineJobServiceImpl(
            jobRepository,
            stepRepository,
            logService,
            kotlinx.serialization.json.Json,
            KubernetesCiDispatchConfiguration(setOf("android")),
            MissingObjectProvider(KubernetesJobDispatchService::class, null),
        )
        coEvery { jobRepository.findCurrentByAgent(agentId) } returns null
        coEvery { jobRepository.findNextAvailable(listOf("android", "linux")) } returns null

        assertNull(configuredService.claimJob(agentId, listOf("android", "linux")))

        coVerify(exactly = 1) {
            jobRepository.findNextAvailable(listOf("android", "linux"))
        }
    }

    @Test
    fun `claimJob fails abandoned running job before claiming new one`() = runTest {
        val agentId = UUID.random()
        val abandonedJob = testJob(name = "old-build", status = PipelineRunStatus.RUNNING)
        val newJob = testJob(name = "new-build")
        val claimedJob = newJob.copy(status = PipelineRunStatus.RUNNING, agentId = agentId)

        coEvery { jobRepository.findCurrentByAgent(agentId) } returns abandonedJob
        coEvery { stepRepository.findByJob(abandonedJob.id) } returns emptyList()
        coEvery { jobRepository.findNextAvailable(listOf("linux")) } returns newJob
        coEvery {
            jobRepository.claimJobById(newJob.id, agentId, null, PipelineRunStatus.RUNNING)
        } returns claimedJob

        val result = service.claimJob(agentId, listOf("linux"))

        assertNotNull(result)
        assertEquals("new-build", result.name)
        coVerify {
            jobRepository.markFinished(
                abandonedJob.id, PipelineRunStatus.FAILURE,
                "Agent reconnected without finishing this job; marked as abandoned"
            )
        }
    }

    @Test
    fun `claimJobById claims only the requested queued job`() = runTest {
        val agentId = UUID.random()
        val job = testJob()
        val claimed = job.copy(status = PipelineRunStatus.RUNNING, agentId = agentId)
        coEvery { jobRepository.findCurrentByAgent(agentId) } returns null
        coEvery {
            jobRepository.claimJobById(job.id, agentId, null, PipelineRunStatus.RUNNING)
        } returns claimed

        val result = service.claimJobById(agentId, job.id)

        assertEquals(job.id, result?.id)
        coVerify(exactly = 0) { jobRepository.findNextAvailable(any()) }
    }

    @Test
    fun `claimJobById transfers an orchestrator reservation to its child agent`() = runTest {
        val orchestratorId = UUID.random()
        val agentId = UUID.random()
        val job = testJob(status = PipelineRunStatus.RUNNING)
        val claimed = job.copy(agentId = agentId)
        coEvery { jobRepository.findCurrentByAgent(agentId) } returns null
        coEvery {
            jobRepository.claimJobById(job.id, agentId, orchestratorId, PipelineRunStatus.RUNNING)
        } returns claimed

        val result = service.claimJobById(agentId, job.id, orchestratorId)

        assertEquals(agentId, result?.agentId)
    }

    @Test
    fun `claimJobById returns a job already held by the same agent`() = runTest {
        val agentId = UUID.random()
        val job = testJob(status = PipelineRunStatus.RUNNING).copy(agentId = agentId)
        coEvery { jobRepository.findCurrentByAgent(agentId) } returns job

        val result = service.claimJobById(agentId, job.id)

        assertEquals(job, result)
        coVerify(exactly = 0) {
            jobRepository.claimJobById(any(), any(), any(), any())
        }
    }

    @Test
    fun `kubernetes dispatch lifecycle operations stay behind the job service`() = runTest {
        val job = testJob()
        val dispatchId = UUID.random()
        val agentId = UUID.random()
        val dispatched = job.copy(
            agentId = agentId,
            kubernetesDispatchId = dispatchId,
        )
        coEvery {
            jobRepository.findNextKubernetesDispatchCandidate(listOf("android"))
        } returns job
        coEvery {
            jobRepository.markKubernetesDispatched(job.id, dispatchId, agentId)
        } returns dispatched
        coEvery {
            jobRepository.findUnfinalizedKubernetesDispatched(100)
        } returns listOf(dispatched)
        coEvery {
            jobRepository.claimKubernetesFinalization(job.id)
        } returns dispatched

        assertEquals(job, service.findNextKubernetesDispatchCandidate(listOf("android")))
        assertEquals(
            dispatched,
            service.markKubernetesDispatched(job.id, dispatchId, agentId),
        )
        assertEquals(
            listOf(dispatched),
            service.findUnfinalizedKubernetesDispatched(100),
        )
        assertEquals(dispatched, service.claimKubernetesFinalization(job.id))
    }

    @Test
    fun `updateStatus marks finished for terminal states`() = runTest {
        val jobId = UUID.random()

        service.updateStatus(jobId, PipelineRunStatus.SUCCESS)
        coVerify { jobRepository.markFinished(jobId, PipelineRunStatus.SUCCESS, null) }

        service.updateStatus(jobId, PipelineRunStatus.FAILURE)
        coVerify { jobRepository.markFinished(jobId, PipelineRunStatus.FAILURE, null) }

        service.updateStatus(jobId, PipelineRunStatus.CANCELLED)
        coVerify { jobRepository.markFinished(jobId, PipelineRunStatus.CANCELLED, null) }
    }

    @Test
    fun `updateStatus persists the failure summary for FAILURE`() = runTest {
        val jobId = UUID.random()
        coEvery { stepRepository.findByJob(jobId) } returns emptyList()

        service.updateStatus(jobId, PipelineRunStatus.FAILURE, "Insufficient disk space")
        coVerify { jobRepository.markFinished(jobId, PipelineRunStatus.FAILURE, "Insufficient disk space") }
    }

    @Test
    fun `finishIfActive reports only the caller that won the terminal transition`() = runTest {
        val jobId = UUID.random()
        val failed = testJob(status = PipelineRunStatus.FAILURE).copy(id = jobId)
        coEvery {
            jobRepository.markFinishedIfActive(
                jobId,
                PipelineRunStatus.FAILURE,
                "ImagePullBackOff",
            )
        } returns failed andThen null
        coEvery { stepRepository.findByJob(jobId) } returns emptyList()

        assertTrue(
            service.finishIfActive(
                jobId,
                PipelineRunStatus.FAILURE,
                "ImagePullBackOff",
            )
        )
        assertEquals(
            false,
            service.finishIfActive(
                jobId,
                PipelineRunStatus.FAILURE,
                "ImagePullBackOff",
            ),
        )
    }

    @Test
    fun `finishIfActive cascades stable reasons for cancelled and skipped jobs`() = runTest {
        val cancelledId = UUID.random()
        val skippedId = UUID.random()
        coEvery {
            jobRepository.markFinishedIfActive(
                cancelledId,
                PipelineRunStatus.CANCELLED,
                null,
            )
        } returns testJob(status = PipelineRunStatus.CANCELLED).copy(id = cancelledId)
        coEvery {
            jobRepository.markFinishedIfActive(
                skippedId,
                PipelineRunStatus.SKIPPED,
                null,
            )
        } returns testJob(status = PipelineRunStatus.SKIPPED).copy(id = skippedId)
        coEvery { stepRepository.findByJob(cancelledId) } returns emptyList()
        coEvery { stepRepository.findByJob(skippedId) } returns emptyList()

        assertTrue(service.finishIfActive(cancelledId, PipelineRunStatus.CANCELLED, null))
        assertTrue(service.finishIfActive(skippedId, PipelineRunStatus.SKIPPED, null))
    }

    @Test
    fun `reapStaleRunningJobs records why each job was failed`() = runTest {
        val orphan = testJob(name = "orphan", status = PipelineRunStatus.RUNNING)
        val slow = testJob(name = "slow", status = PipelineRunStatus.RUNNING)
        coEvery { jobRepository.findOrphanedRunning() } returns listOf(orphan)
        coEvery { jobRepository.findTimedOutRunning() } returns listOf(slow)
        coEvery { stepRepository.findByJob(any()) } returns emptyList()

        val reaped = service.reapStaleRunningJobs()

        assertEquals(2, reaped.size)
        coVerify {
            jobRepository.markFinished(
                orphan.id, PipelineRunStatus.FAILURE,
                "Agent stopped heartbeating while the job was running"
            )
        }
        coVerify {
            jobRepository.markFinished(
                slow.id, PipelineRunStatus.FAILURE,
                "Job exceeded its 60 minute timeout"
            )
        }
    }

    @Test
    fun `reapStaleRunningJobs cancels its kubernetes workload`() = runTest {
        val dispatchId = UUID.random()
        val timedOut = testJob(
            name = "timed-out-kubernetes-job",
            status = PipelineRunStatus.RUNNING,
            kubernetesDispatchId = dispatchId,
        )
        coEvery { jobRepository.findOrphanedRunning() } returns emptyList()
        coEvery { jobRepository.findTimedOutRunning() } returns listOf(timedOut)
        coEvery { stepRepository.findByJob(timedOut.id) } returns emptyList()

        service.reapStaleRunningJobs()

        coVerify(exactly = 1) { kubernetesJobDispatchService.cancel(dispatchId) }
        coVerify(exactly = 1) {
            jobRepository.markFinished(
                timedOut.id,
                PipelineRunStatus.FAILURE,
                "Job exceeded its 60 minute timeout",
            )
        }
    }

    @Test
    fun `reaped FAILURE gives running steps the reason plus the persisted log tail`() = runTest {
        val orphan = testJob(name = "orphan", status = PipelineRunStatus.RUNNING)
        val runningStep = testStep(name = "Build", jobId = orphan.id, status = PipelineRunStatus.RUNNING)
        val run = testRun(orphan.pipelineRunId)

        coEvery { jobRepository.findOrphanedRunning() } returns listOf(orphan)
        coEvery { jobRepository.findTimedOutRunning() } returns emptyList()
        coEvery { jobRepository.findById(orphan.id) } returns orphan
        coEvery { runService.findById(orphan.pipelineRunId) } returns run
        coEvery { stepRepository.findByJob(orphan.id) } returns listOf(runningStep)
        coEvery {
            logService.getLogs(run.repositoryId, run.id, orphan.id, runningStep.id, any(), any(), any(), any())
        } returns listOf(
            LogLine(lineNumber = 41, timestamp = "t1", content = "compiling module", stream = LogStream.STDOUT),
            LogLine(lineNumber = 42, timestamp = "t2", content = "error: compilation failed", stream = LogStream.STDERR)
        )

        service.reapStaleRunningJobs()

        coVerify {
            stepRepository.markFinished(
                runningStep.id, PipelineRunStatus.FAILURE, null,
                match<String> {
                    it.startsWith("Agent stopped heartbeating while the job was running") &&
                        it.contains("Last log output:") &&
                        // stderr is preferred over stdout, mirroring the CLI summary
                        it.contains("error: compilation failed") &&
                        !it.contains("compiling module")
                }
            )
        }
    }

    @Test
    fun `reaped FAILURE still terminates steps with the reason when the log read fails`() = runTest {
        val orphan = testJob(name = "orphan", status = PipelineRunStatus.RUNNING)
        val runningStep = testStep(name = "Build", jobId = orphan.id, status = PipelineRunStatus.RUNNING)
        val run = testRun(orphan.pipelineRunId)

        coEvery { jobRepository.findOrphanedRunning() } returns listOf(orphan)
        coEvery { jobRepository.findTimedOutRunning() } returns emptyList()
        coEvery { jobRepository.findById(orphan.id) } returns orphan
        coEvery { runService.findById(orphan.pipelineRunId) } returns run
        coEvery { stepRepository.findByJob(orphan.id) } returns listOf(runningStep)
        coEvery {
            logService.getLogs(any(), any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("object storage unavailable")

        val reaped = service.reapStaleRunningJobs()

        assertEquals(1, reaped.size)
        coVerify {
            stepRepository.markFinished(
                runningStep.id, PipelineRunStatus.FAILURE, null,
                "Agent stopped heartbeating while the job was running"
            )
        }
    }

    @Test
    fun `cascaded FAILURE gives queued steps the reason without reading logs`() = runTest {
        val jobId = UUID.random()
        val queuedStep = testStep(name = "Deploy", jobId = jobId, status = PipelineRunStatus.QUEUED)
        coEvery { stepRepository.findByJob(jobId) } returns listOf(queuedStep)

        service.updateStatus(jobId, PipelineRunStatus.FAILURE)

        coVerify {
            stepRepository.markFinished(
                queuedStep.id, PipelineRunStatus.FAILURE, null,
                "Step terminated because the job failed"
            )
        }
        coVerify(exactly = 0) { logService.getLogs(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancelBlockedJobs records why each step was cancelled`() = runTest {
        val dispatchId = UUID.random()
        val job = testJob(name = "blocked", status = PipelineRunStatus.QUEUED)
            .copy(kubernetesDispatchId = dispatchId)
        val queuedStep = testStep(name = "Test", jobId = job.id, status = PipelineRunStatus.QUEUED)
        coEvery { jobRepository.cancelBlockedJobs(runId) } returns listOf(job) andThen emptyList()
        coEvery { stepRepository.findByJob(job.id) } returns listOf(queuedStep)

        val cancelled = service.cancelBlockedJobs(runId)

        assertEquals(1, cancelled.size)
        coVerify {
            stepRepository.markFinished(
                queuedStep.id, PipelineRunStatus.CANCELLED, null,
                "Job was cancelled before this step ran"
            )
        }
        coVerify(exactly = 1) { kubernetesJobDispatchService.cancel(dispatchId) }
        coVerify(exactly = 0) { logService.getLogs(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateStatus calls updateStatus for non-terminal states`() = runTest {
        val jobId = UUID.random()

        service.updateStatus(jobId, PipelineRunStatus.QUEUED)
        coVerify { jobRepository.updateStatus(jobId, PipelineRunStatus.QUEUED) }
    }

    @Test
    fun `updateStepStatus marks started for RUNNING`() = runTest {
        val stepId = UUID.random()
        service.updateStepStatus(stepId, PipelineRunStatus.RUNNING)
        coVerify { stepRepository.markStarted(stepId, PipelineRunStatus.RUNNING, null) }
    }

    @Test
    fun `updateStepStatus marks finished with exit code for SUCCESS`() = runTest {
        val stepId = UUID.random()
        service.updateStepStatus(stepId, PipelineRunStatus.SUCCESS, 0)
        coVerify { stepRepository.markFinished(stepId, PipelineRunStatus.SUCCESS, 0, null) }
    }

    @Test
    fun `updateStepStatus marks finished with exit code for FAILURE`() = runTest {
        val stepId = UUID.random()
        service.updateStepStatus(stepId, PipelineRunStatus.FAILURE, 1)
        coVerify { stepRepository.markFinished(stepId, PipelineRunStatus.FAILURE, 1, null) }
    }

    @Test
    fun `updateStepStatus persists the failure summary for FAILURE`() = runTest {
        val stepId = UUID.random()
        val summary = "FAILURE: Build failed with an exception.\n* What went wrong:\nCompilation error"
        service.updateStepStatus(stepId, PipelineRunStatus.FAILURE, 1, summary)
        coVerify { stepRepository.markFinished(stepId, PipelineRunStatus.FAILURE, 1, summary) }
    }

    @Test
    fun `getSteps delegates to step repository`() = runTest {
        val jobId = UUID.random()
        val steps = listOf(
            testStep(ordinal = 0, name = "Checkout"),
            testStep(ordinal = 1, name = "Build")
        )
        coEvery { stepRepository.findByJob(jobId) } returns steps

        val result = service.getSteps(jobId)
        assertEquals(2, result.size)
        assertEquals("Checkout", result[0].name)
    }

    @Test
    fun `findByRun delegates to job repository`() = runTest {
        val jobs = listOf(testJob(name = "build"), testJob(name = "test"))
        coEvery { jobRepository.findByRun(runId) } returns jobs

        val result = service.findByRun(runId)
        assertEquals(2, result.size)
    }

    @Test
    fun `findById returns job when found`() = runTest {
        val job = testJob()
        coEvery { jobRepository.findById(job.id) } returns job

        assertNotNull(service.findById(job.id))
    }

    @Test
    fun `findById returns null when not found`() = runTest {
        coEvery { jobRepository.findById(any()) } returns null
        assertNull(service.findById(UUID.random()))
    }

    @Test
    fun `bypassRequirements records an attributable override for a queued gated job`() = runTest {
        val requestedBy = UUID.random()
        val gated = testJob().copy(
            pipelineRequirements = kotlinx.serialization.json.Json.parseToJsonElement(
                """[{"repository":"bosca","pipeline":"build"}]"""
            ),
        )
        val bypassed = gated.copy(
            requirementsSatisfiedAt = bosca.serialization.OffsetDateTime.now(),
            requirementsBypassedAt = bosca.serialization.OffsetDateTime.now(),
            requirementsBypassedBy = requestedBy,
            requirementsBypassReason = "release is blocked",
        )
        coEvery { jobRepository.findById(gated.id) } returns gated
        coEvery {
            jobRepository.bypassRequirements(gated.id, requestedBy, "release is blocked")
        } returns bypassed

        val result = service.bypassRequirements(gated.id, requestedBy, "  release is blocked  ")

        assertEquals(bypassed, result)
        coVerify(exactly = 1) {
            jobRepository.bypassRequirements(gated.id, requestedBy, "release is blocked")
        }
    }

    @Test
    fun `bypassRequirements rejects an ungated or already satisfied job`() = runTest {
        val requestedBy = UUID.random()
        val ungated = testJob()
        coEvery { jobRepository.findById(ungated.id) } returns ungated

        assertFailsWith<IllegalStateException> {
            service.bypassRequirements(ungated.id, requestedBy)
        }

        val satisfied = ungated.copy(
            requirements = kotlinx.serialization.json.Json.parseToJsonElement(
                """[{"type":"maven","namespace":"releases","coordinate":"io.bosca:test:1"}]"""
            ),
            requirementsSatisfiedAt = bosca.serialization.OffsetDateTime.now(),
        )
        coEvery { jobRepository.findById(satisfied.id) } returns satisfied

        assertFailsWith<IllegalStateException> {
            service.bypassRequirements(satisfied.id, requestedBy)
        }
        coVerify(exactly = 0) { jobRepository.bypassRequirements(any(), any(), any()) }
    }

    @Test
    fun `findCurrentByAgent returns running job`() = runTest {
        val agentId = UUID.random()
        val job = testJob(status = PipelineRunStatus.RUNNING)
        coEvery { jobRepository.findCurrentByAgent(agentId) } returns job

        val result = service.findCurrentByAgent(agentId)
        assertNotNull(result)
        assertEquals(PipelineRunStatus.RUNNING, result.status)
    }

    @Test
    fun `findCurrentByAgent returns null when idle`() = runTest {
        coEvery { jobRepository.findCurrentByAgent(any()) } returns null
        assertNull(service.findCurrentByAgent(UUID.random()))
    }

    @Test
    fun `findByAgent returns recent jobs`() = runTest {
        val agentId = UUID.random()
        val jobs = listOf(testJob(name = "build"), testJob(name = "test"))
        coEvery { jobRepository.findByAgent(agentId, 10) } returns jobs

        val result = service.findByAgent(agentId, 10)
        assertEquals(2, result.size)
    }

    private fun testJob(
        id: UUID = UUID.random(),
        name: String = "build",
        status: PipelineRunStatus = PipelineRunStatus.QUEUED,
        dependsOn: List<String> = emptyList(),
        kubernetesDispatchId: UUID? = null,
    ) = PipelineJob(
        id = id,
        pipelineRunId = runId,
        name = name,
        status = status,
        runnerLabel = "linux",
        dependsOn = dependsOn,
        kubernetesDispatchId = kubernetesDispatchId,
    )

    private fun testStep(
        ordinal: Int = 0,
        name: String = "Step",
        jobId: UUID = UUID.random(),
        status: PipelineRunStatus = PipelineRunStatus.QUEUED
    ) = PipelineStep(
        id = UUID.random(),
        pipelineJobId = jobId,
        name = name,
        ordinal = ordinal,
        status = status
    )

    private fun testRun(id: UUID) = PipelineRun(
        id = id,
        pipelineId = UUID.random(),
        repositoryId = UUID.random(),
        commitSha = "abc",
        ref = "main",
        triggerType = PipelineTriggerType.PUSH,
        status = PipelineRunStatus.RUNNING,
        number = 1
    )
}

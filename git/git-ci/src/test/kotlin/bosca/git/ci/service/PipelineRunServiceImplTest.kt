@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.service

import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.asProvider
import bosca.di.provides
import bosca.git.ci.repository.PipelineRunRepository
import bosca.git.model.ArtifactDefinition
import bosca.git.model.EnvironmentDefinition
import bosca.git.model.JobDefinition
import bosca.git.model.Pipeline
import bosca.git.model.PipelineAgent
import bosca.git.model.PipelineArtifact
import bosca.git.model.PipelineConcurrency
import bosca.git.model.PipelineDefinition
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineStep
import bosca.git.model.PipelineTrigger
import bosca.git.model.PipelineTriggerType
import bosca.git.model.StepDefinition
import bosca.git.model.TriggerInput
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineArtifactService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineLogService
import bosca.git.service.PipelineService
import bosca.git.service.WebhookService
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.pubsub.PubSubService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PipelineRunServiceImplTest {

    private val runRepository = mockk<PipelineRunRepository>(relaxed = true)
    private val jobService = mockk<PipelineJobService>(relaxed = true)
    private val webhookService = mockk<WebhookService>(relaxed = true)
    private val pipelineService = mockk<PipelineService>(relaxed = true)
    private val agentService = mockk<PipelineAgentService>(relaxed = true)
    private val kubernetesDispatchService = mockk<KubernetesJobDispatchService>(relaxed = true)
    private val artifactService = mockk<PipelineArtifactService>(relaxed = true)
    private val logService = mockk<PipelineLogService>(relaxed = true)
    private lateinit var service: PipelineRunServiceImpl

    private val pipelineId = UUID.random()
    private val repoId = UUID.random()

    private val definition = PipelineDefinition(
        name = "Build",
        triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH)),
        jobs = mapOf(
            "build" to JobDefinition(steps = listOf(StepDefinition(name = "Build", run = "build")))
        )
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<PubSubService>(singleton = true) { mockk(relaxed = true) }
        coEvery { runRepository.getMaxNumber(any()) } returns 0
        coEvery { runRepository.create(any()) } answers { (firstArg() as PipelineRun).copy(id = UUID.random()) }
        coEvery { runRepository.findById(any()) } returns null
        service = PipelineRunServiceImpl(
            runRepository,
            jobService,
            webhookService,
            pipelineService,
            Json,
            agentService,
            kubernetesDispatchService.asProvider(),
            artifactService,
            logService,
        )
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `automatic trigger redelivery neither creates jobs nor cancels runs again`() = runTest {
        val triggerId = UUID.random()
        val principalId = UUID.random()
        val automaticDefinition = definition.copy(concurrency = PipelineConcurrency(group = "build", cancelInProgress = true))
        coEvery { runRepository.reserveTrigger(triggerId, pipelineId) } returnsMany listOf(1, 0)
        val connection = mockk<ConnectionManager>(relaxed = true)
        withContext(connection.asCoroutineContext()) {
            val run = service.createTriggeredRun(triggerId, pipelineId, repoId, automaticDefinition, "sha", "main", PipelineTriggerType.PUSH, principalId)
            assertEquals(principalId, assertNotNull(run).triggeredBy)
            assertEquals(null, service.createTriggeredRun(triggerId, pipelineId, repoId, automaticDefinition, "sha", "main", PipelineTriggerType.PUSH, principalId))
        }
        coVerify(exactly = 1) { runRepository.create(any()) }
        coVerify(exactly = 1) { jobService.createJobs(any(), any()) }
        coVerify(exactly = 1) { runRepository.findActiveByConcurrencyGroup("build") }
    }

    @Test
    fun `manual runs cannot reserve automatic trigger occurrences`() = runTest {
        val connection = mockk<ConnectionManager>(relaxed = true)
        withContext(connection.asCoroutineContext()) {
            assertFailsWith<IllegalArgumentException> {
                service.createTriggeredRun(UUID.random(), pipelineId, repoId, definition, "sha", "main", PipelineTriggerType.MANUAL, UUID.random())
            }
        }
        coVerify(exactly = 0) { runRepository.reserveTrigger(any(), any()) }
        coVerify(exactly = 0) { runRepository.create(any()) }
    }

    @Test
    fun `createRun creates run with auto-incrementing number`() = runTest {
        coEvery { runRepository.getMaxNumber(pipelineId) } returns 5

        val captured = slot<PipelineRun>()
        coEvery { runRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }

        service.createRun(pipelineId, repoId, definition, "abc123", "refs/heads/main", PipelineTriggerType.PUSH)

        assertEquals(6, captured.captured.number)
        assertEquals(PipelineRunStatus.QUEUED, captured.captured.status)
        assertEquals("abc123", captured.captured.commitSha)
        assertEquals("refs/heads/main", captured.captured.ref)
    }

    @Test
    fun `findByReleaseId delegates to the persisted release parameter lookup`() = runTest {
        val releaseId = UUID.random()
        val expected = PipelineRun(
            pipelineId = pipelineId,
            repositoryId = repoId,
            commitSha = "abc123",
            ref = "refs/heads/main",
            triggerType = PipelineTriggerType.RELEASE,
        )
        coEvery { runRepository.findByReleaseId(releaseId.toString(), 5, 12) } returns listOf(expected)

        assertEquals(listOf(expected), service.findByReleaseId(releaseId, 5, 12))
    }

    @Test
    fun `delete removes terminal run logs artifacts and database row`() = runTest {
        val runId = UUID.random()
        val job = PipelineJob(id = UUID.random(), pipelineRunId = runId, name = "build")
        val step = PipelineStep(id = UUID.random(), pipelineJobId = job.id, name = "Build", ordinal = 0)
        val run = PipelineRun(
            id = runId,
            pipelineId = pipelineId,
            repositoryId = repoId,
            commitSha = "abc123",
            ref = "refs/heads/main",
            triggerType = PipelineTriggerType.PUSH,
            status = PipelineRunStatus.FAILURE,
            number = 7,
        )
        val artifact = PipelineArtifact(
            repositoryId = repoId,
            pipelineRunId = runId,
            runNumber = run.number,
            name = "server",
        )
        coEvery { runRepository.findByIdForUpdate(runId) } returns run
        coEvery { jobService.findByRun(runId) } returns listOf(job)
        coEvery { jobService.getSteps(job.id) } returns listOf(step)
        coEvery { artifactService.listByRun(runId) } returns listOf(artifact)
        coEvery { runRepository.delete(runId) } returns run

        assertEquals(run, service.delete(runId))

        coVerify { logService.deleteStepLog(repoId, runId, job.id, step.id) }
        coVerify { artifactService.delete(repoId, run.number, artifact.name) }
        coVerify { runRepository.delete(runId) }
    }

    @Test
    fun `delete rejects an active run without removing persisted data`() = runTest {
        val run = PipelineRun(
            id = UUID.random(),
            pipelineId = pipelineId,
            repositoryId = repoId,
            commitSha = "abc123",
            ref = "refs/heads/main",
            triggerType = PipelineTriggerType.PUSH,
            status = PipelineRunStatus.RUNNING,
            number = 8,
        )
        coEvery { runRepository.findByIdForUpdate(run.id) } returns run

        assertFailsWith<IllegalStateException> { service.delete(run.id) }

        coVerify(exactly = 0) { jobService.findByRun(any()) }
        coVerify(exactly = 0) { artifactService.listByRun(any()) }
        coVerify(exactly = 0) { runRepository.delete(any()) }
    }

    @Test
    fun `plan applies trigger inputs conditions and environment selection without persisting`() = runTest {
        val planned = service.plan(
            definition = PipelineDefinition(
                name = "Release",
                triggers = listOf(PipelineTrigger(type = PipelineTriggerType.RELEASE)),
                jobs = mapOf(
                    "release" to JobDefinition(
                        condition = "event == 'release'",
                        steps = listOf(StepDefinition(name = "Release", uses = "tag")),
                    ),
                    "promotion" to JobDefinition(
                        condition = "event == 'promotion'",
                        steps = listOf(StepDefinition(name = "Deploy", uses = "deploy")),
                    ),
                ),
            ),
            ref = "refs/heads/main",
            triggerType = PipelineTriggerType.RELEASE,
            parameters = mapOf("release.id" to UUID.random().toString()),
        )

        assertEquals(setOf("release"), planned.jobs.keys)
        assertTrue("release.id" in planned.parameters)
        coVerify(exactly = 0) { runRepository.create(any()) }
    }

    @Test
    fun `createRun creates jobs from definition`() = runTest {
        service.createRun(pipelineId, repoId, definition, "abc123", "refs/heads/main", PipelineTriggerType.PUSH)

        coVerify { jobService.createJobs(any(), definition.jobs) }
    }

    @Test
    fun `createRun flattens workflow-level env into every step`() = runTest {
        val defWithEnv = PipelineDefinition(
            name = "Build",
            env = mapOf("APPLE_TEAM_ID" to "AB12CD34EF", "GRADLE_OPTS" to "-Xmx4g"),
            jobs = mapOf(
                "build" to JobDefinition(steps = listOf(
                    StepDefinition(name = "Archive", run = "xcodebuild archive"),
                    StepDefinition(name = "Export", run = "xcodebuild -exportArchive", env = mapOf("EXPORT_ONLY" to "1"))
                )),
                "notify" to JobDefinition(steps = listOf(
                    StepDefinition(name = "Notify", uses = "notify")
                ))
            )
        )

        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(pipelineId, repoId, defWithEnv, "abc123", "refs/heads/main", PipelineTriggerType.PUSH)

        val buildSteps = captured.captured.getValue("build").steps
        assertEquals(
            mapOf("APPLE_TEAM_ID" to "AB12CD34EF", "GRADLE_OPTS" to "-Xmx4g"),
            buildSteps[0].env
        )
        assertEquals(
            mapOf("APPLE_TEAM_ID" to "AB12CD34EF", "GRADLE_OPTS" to "-Xmx4g", "EXPORT_ONLY" to "1"),
            buildSteps[1].env
        )
        assertEquals(
            mapOf("APPLE_TEAM_ID" to "AB12CD34EF", "GRADLE_OPTS" to "-Xmx4g"),
            captured.captured.getValue("notify").steps[0].env
        )
    }

    @Test
    fun `createRun lets step env override workflow env on key conflict`() = runTest {
        val defWithEnv = PipelineDefinition(
            name = "Build",
            env = mapOf("LOG_LEVEL" to "info"),
            jobs = mapOf(
                "build" to JobDefinition(steps = listOf(
                    StepDefinition(name = "Build", run = "build", env = mapOf("LOG_LEVEL" to "debug"))
                ))
            )
        )

        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(pipelineId, repoId, defWithEnv, "abc123", "refs/heads/main", PipelineTriggerType.PUSH)

        assertEquals(
            mapOf("LOG_LEVEL" to "debug"),
            captured.captured.getValue("build").steps[0].env
        )
    }

    @Test
    fun `createRun injects trigger parameters into every step, overriding workflow and step env`() = runTest {
        val defWithEnv = PipelineDefinition(
            name = "Build",
            env = mapOf("BOSCA_VERSION" to "0.0.1", "GRADLE_OPTS" to "-Xmx4g"),
            jobs = mapOf(
                "build" to JobDefinition(steps = listOf(
                    StepDefinition(name = "Compile", run = "./gradlew build"),
                    // A step that also sets BOSCA_VERSION must still lose to the trigger parameter.
                    StepDefinition(name = "Publish", run = "./gradlew publish", env = mapOf("BOSCA_VERSION" to "step-local"))
                ))
            )
        )

        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(
            pipelineId, repoId, defWithEnv, "abc123", "refs/heads/main", PipelineTriggerType.MANUAL,
            parameters = mapOf("BOSCA_VERSION" to "2.0.0"),
        )

        val steps = captured.captured.getValue("build").steps
        // The trigger parameter wins over the workflow default...
        assertEquals(mapOf("BOSCA_VERSION" to "2.0.0", "GRADLE_OPTS" to "-Xmx4g"), steps[0].env)
        // ...and over a step that set the same key.
        assertEquals(mapOf("BOSCA_VERSION" to "2.0.0", "GRADLE_OPTS" to "-Xmx4g"), steps[1].env)
    }

    @Test
    fun `createRun flattens trigger parameters even when the definition has no env`() = runTest {
        val def = PipelineDefinition(
            name = "Build",
            jobs = mapOf("build" to JobDefinition(steps = listOf(StepDefinition(name = "Compile", run = "build")))),
        )
        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(
            pipelineId, repoId, def, "abc123", "refs/heads/main", PipelineTriggerType.MANUAL,
            parameters = mapOf("BOSCA_VERSION" to "2.0.0"),
        )

        assertEquals(mapOf("BOSCA_VERSION" to "2.0.0"), captured.captured.getValue("build").steps[0].env)
    }

    @Test
    fun `createRun resolves declared artifact coordinates against branch and trigger parameters`() = runTest {
        val defWithArtifacts = PipelineDefinition(
            name = "Build",
            jobs = mapOf(
                "publish" to JobDefinition(
                    steps = listOf(StepDefinition(name = "Publish", run = "./gradlew publish")),
                    artifacts = listOf(
                        ArtifactDefinition(type = "maven", namespace = "bosca-maven", coordinate = "io.bosca:x:\${{ env.VERSION }}"),
                        ArtifactDefinition(type = "docker", namespace = "bosca-docker", coordinate = "kctl:\${{ branch }}"),
                    ),
                ),
            ),
        )
        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(
            pipelineId, repoId, defWithArtifacts, "abc123", "refs/heads/main", PipelineTriggerType.MANUAL,
            parameters = mapOf("VERSION" to "2.0.1"),
        )

        // The trigger parameter (VERSION) rides in as env; branch is the stripped ref.
        val artifacts = captured.captured.getValue("publish").artifacts
        assertEquals("io.bosca:x:2.0.1", artifacts[0].coordinate)
        assertEquals("kctl:main", artifacts[1].coordinate)
    }

    @Test
    fun `artifacts reads declared artifacts and dedups matrix duplicates`() = runTest {
        val runId = UUID.random()
        val arts = listOf(
            ArtifactDefinition(type = "maven", namespace = "bosca-maven", coordinate = "io.bosca:x:2.0.1"),
            ArtifactDefinition(type = "docker", namespace = "bosca-docker", coordinate = "kctl:2.0.1"),
        )
        val encoded = Json.encodeToJsonElement(ListSerializer(ArtifactDefinition.serializer()), arts)
        // Two matrix-expanded job rows carry the same declared artifacts.
        coEvery { jobService.findByRun(runId) } returns listOf(
            PipelineJob(pipelineRunId = runId, name = "build (amd64)", artifacts = encoded),
            PipelineJob(pipelineRunId = runId, name = "build (arm64)", artifacts = encoded),
        )

        assertEquals(arts, service.artifacts(runId))
    }

    @Test
    fun `artifacts is empty when jobs declared no artifacts`() = runTest {
        val runId = UUID.random()
        coEvery { jobService.findByRun(runId) } returns listOf(PipelineJob(pipelineRunId = runId, name = "build"))

        assertEquals(emptyList<ArtifactDefinition>(), service.artifacts(runId))
    }

    @Test
    fun `createRun sets concurrency group from definition`() = runTest {
        val defWithConcurrency = definition.copy(
            concurrency = PipelineConcurrency(group = "ci-\${{ ref }}", cancelInProgress = false)
        )

        val captured = slot<PipelineRun>()
        coEvery { runRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }

        service.createRun(pipelineId, repoId, defWithConcurrency, "abc", "refs/heads/main", PipelineTriggerType.PUSH)

        assertEquals("ci-refs/heads/main", captured.captured.concurrencyGroup)
    }

    @Test
    fun `createRun cancels superseded runs when cancel-in-progress is true`() = runTest {
        val defWithCancel = definition.copy(
            concurrency = PipelineConcurrency(group = "ci-main", cancelInProgress = true)
        )

        val existingRun = testRun(status = PipelineRunStatus.RUNNING, concurrencyGroup = "ci-main")
        coEvery { runRepository.findActiveByConcurrencyGroup("ci-main") } returns listOf(existingRun)
        coEvery { runRepository.findById(existingRun.id) } returns existingRun
        coEvery { jobService.findByRun(existingRun.id) } returns listOf(
            testJob(status = PipelineRunStatus.RUNNING)
        )

        service.createRun(pipelineId, repoId, defWithCancel, "abc", "refs/heads/main", PipelineTriggerType.PUSH)

        coVerify { runRepository.markFinished(existingRun.id, PipelineRunStatus.CANCELLED) }
    }

    @Test
    fun `createRun does not cancel when cancel-in-progress is false`() = runTest {
        val defNoCancelConcurrency = definition.copy(
            concurrency = PipelineConcurrency(group = "ci-main", cancelInProgress = false)
        )

        service.createRun(pipelineId, repoId, defNoCancelConcurrency, "abc", "refs/heads/main", PipelineTriggerType.PUSH)

        coVerify(exactly = 0) { runRepository.findActiveByConcurrencyGroup(any()) }
    }

    @Test
    fun `createRun sets null concurrency group when not configured`() = runTest {
        val captured = slot<PipelineRun>()
        coEvery { runRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }

        service.createRun(pipelineId, repoId, definition, "abc", "refs/heads/main", PipelineTriggerType.PUSH)

        assertEquals(null, captured.captured.concurrencyGroup)
    }

    @Test
    fun `createRun records triggeredBy profile`() = runTest {
        val profileId = UUID.random()
        val captured = slot<PipelineRun>()
        coEvery { runRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }

        service.createRun(pipelineId, repoId, definition, "abc", "refs/heads/main", PipelineTriggerType.MANUAL, profileId)

        assertEquals(profileId, captured.captured.triggeredBy)
        assertEquals(PipelineTriggerType.MANUAL, captured.captured.triggerType)
    }

    @Test
    fun `cancelRun cancels all pending jobs and updates run status`() = runTest {
        val run = testRun(status = PipelineRunStatus.RUNNING)
        val job1 = testJob(pipelineRunId = run.id, status = PipelineRunStatus.RUNNING)
        val job2 = testJob(pipelineRunId = run.id, status = PipelineRunStatus.QUEUED)
        val job3 = testJob(pipelineRunId = run.id, status = PipelineRunStatus.SUCCESS)

        coEvery { runRepository.findById(run.id) } returns run
        coEvery { jobService.findByRun(run.id) } returns listOf(job1, job2, job3)
        coEvery {
            jobService.finishIfActive(any(), PipelineRunStatus.CANCELLED, "Pipeline run cancelled")
        } returns true

        service.cancelRun(run.id)

        coVerify {
            jobService.finishIfActive(job1.id, PipelineRunStatus.CANCELLED, "Pipeline run cancelled")
        }
        coVerify {
            jobService.finishIfActive(job2.id, PipelineRunStatus.CANCELLED, "Pipeline run cancelled")
        }
        coVerify(exactly = 0) { jobService.finishIfActive(job3.id, any(), any()) }
        coVerify { runRepository.markFinished(run.id, PipelineRunStatus.CANCELLED) }
    }

    @Test
    fun `cancelRun retires an assigned ephemeral agent`() = runTest {
        val run = testRun(status = PipelineRunStatus.RUNNING)
        val agentId = UUID.random()
        val dispatchId = UUID.random()
        val job = testJob(pipelineRunId = run.id, status = PipelineRunStatus.RUNNING).copy(
            agentId = agentId,
            kubernetesDispatchId = dispatchId,
        )
        coEvery { runRepository.findById(run.id) } returns run
        coEvery { jobService.findByRun(run.id) } returns listOf(job)
        coEvery {
            jobService.finishIfActive(job.id, PipelineRunStatus.CANCELLED, "Pipeline run cancelled")
        } returns true
        coEvery { agentService.findById(agentId) } returns PipelineAgent(
            id = agentId,
            name = "kubernetes-cancelled",
            ephemeral = true,
            jobId = job.id,
            tokenHash = "unused",
        )

        service.cancelRun(run.id)

        coVerify(exactly = 1) { kubernetesDispatchService.cancel(dispatchId) }
        coVerify(exactly = 1) { agentService.deregister(agentId) }
    }

    @Test
    fun `cancelRun is no-op for already finished run`() = runTest {
        val run = testRun(status = PipelineRunStatus.SUCCESS)
        coEvery { runRepository.findById(run.id) } returns run

        service.cancelRun(run.id)

        coVerify(exactly = 0) { jobService.findByRun(any()) }
        coVerify(exactly = 0) { runRepository.markFinished(any(), any()) }
    }

    @Test
    fun `cancelRun is no-op when run not found`() = runTest {
        coEvery { runRepository.findById(any()) } returns null

        service.cancelRun(UUID.random())

        coVerify(exactly = 0) { runRepository.markFinished(any(), any()) }
    }

    @Test
    fun `updateStatus marks started for RUNNING`() = runTest {
        val run = testRun()
        coEvery { runRepository.findById(run.id) } returns run

        service.updateStatus(run.id, PipelineRunStatus.RUNNING)

        coVerify { runRepository.markStarted(run.id, PipelineRunStatus.RUNNING) }
    }

    @Test
    fun `updateStatus marks finished for terminal states`() = runTest {
        val run = testRun()
        coEvery { runRepository.findById(run.id) } returns run

        service.updateStatus(run.id, PipelineRunStatus.SUCCESS)
        coVerify { runRepository.markFinished(run.id, PipelineRunStatus.SUCCESS) }
    }

    @Test
    fun `updateStatus dispatches webhook on RUNNING`() = runTest {
        val run = testRun()
        coEvery { runRepository.findById(run.id) } returns run

        service.updateStatus(run.id, PipelineRunStatus.RUNNING)

        coVerify { webhookService.dispatch(run.repositoryId, any(), any()) }
    }

    @Test
    fun `updateStatus dispatches webhook on completion`() = runTest {
        val run = testRun()
        coEvery { runRepository.findById(run.id) } returns run

        service.updateStatus(run.id, PipelineRunStatus.SUCCESS)

        coVerify { webhookService.dispatch(run.repositoryId, any(), any()) }
    }

    @Test
    fun `rerun creates new run preserving the original trigger and parameters`() = runTest {
        val original = testRun(status = PipelineRunStatus.FAILURE).copy(
            triggerType = PipelineTriggerType.PROMOTION,
            parameters = kotlinx.serialization.json.buildJsonObject {
                put("promotion.environment", kotlinx.serialization.json.JsonPrimitive("production"))
                put("inputs.phasedRelease", kotlinx.serialization.json.JsonPrimitive("false"))
            },
        )
        coEvery { runRepository.findById(original.id) } returns original
        coEvery { runRepository.getMaxNumber(original.pipelineId) } returns 3
        coEvery { pipelineService.findById(original.pipelineId) } returns Pipeline(
            id = original.pipelineId,
            repositoryId = repoId,
            filePath = ".bosca/pipelines/build.yaml",
            name = "Build",
            configHash = "abc"
        )
        coEvery { pipelineService.parseDefinition(repoId, original.ref, any()) } returns releaseDefinition

        val captured = slot<PipelineRun>()
        coEvery { runRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }

        val profileId = UUID.random()
        val result = service.rerun(original.id, profileId)

        assertEquals(original.commitSha, captured.captured.commitSha)
        assertEquals(original.ref, captured.captured.ref)
        // A re-run means the same run again: the original trigger and parameters carry over — the
        // originally-submitted input value wins over the declared default.
        assertEquals(PipelineTriggerType.PROMOTION, captured.captured.triggerType)
        assertEquals(
            "false",
            (captured.captured.parameters as kotlinx.serialization.json.JsonObject)
                .getValue("inputs.phasedRelease").let { (it as kotlinx.serialization.json.JsonPrimitive).content },
        )
        assertEquals(profileId, captured.captured.triggeredBy)
        assertEquals(4, captured.captured.number)
        assertEquals(PipelineRunStatus.QUEUED, captured.captured.status)
    }

    @Test
    fun `rerun throws when original not found`() = runTest {
        coEvery { runRepository.findById(any()) } returns null

        assertFailsWith<NoSuchElementException> {
            service.rerun(UUID.random())
        }
    }

    @Test
    fun `findByPipeline delegates with pagination`() = runTest {
        val runs = listOf(testRun(), testRun())
        coEvery { runRepository.findByPipeline(pipelineId, 10, 5) } returns runs

        val result = service.findByPipeline(pipelineId, 10, 5)
        assertEquals(2, result.size)
    }

    @Test
    fun `findByRepository delegates with pagination`() = runTest {
        val runs = listOf(testRun())
        coEvery { runRepository.findByRepository(repoId, 0, 25) } returns runs

        val result = service.findByRepository(repoId, 0, 25)
        assertEquals(1, result.size)
    }

    // ── Release/promotion triggers, inputs, and job selection ──────

    private val releaseDefinition = PipelineDefinition(
        name = "Platform Release",
        triggers = listOf(
            PipelineTrigger(type = PipelineTriggerType.RELEASE),
            PipelineTrigger(
                type = PipelineTriggerType.PROMOTION,
                environments = listOf("production"),
                inputs = mapOf(
                    "phasedRelease" to TriggerInput(type = "boolean", default = "true"),
                    "track" to TriggerInput(type = "choice", options = listOf("internal", "production"), default = "production"),
                ),
            ),
        ),
        jobs = mapOf(
            "tag-all" to JobDefinition(
                condition = "event == 'release'",
                steps = listOf(StepDefinition(name = "Tag", run = "tag")),
            ),
            "deploy-production" to JobDefinition(
                condition = "event == 'promotion' && promotion.environment == 'production'",
                needs = listOf("tag-all"),
                steps = listOf(StepDefinition(name = "Deploy", run = "deploy")),
            ),
        ),
    )

    @Test
    fun `a release run keeps only release-phase jobs`() = runTest {
        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(
            pipelineId, repoId, releaseDefinition, "abc", "refs/heads/main", PipelineTriggerType.RELEASE,
            parameters = mapOf("release.id" to "r1", "release.version" to "6.2.0"),
        )

        assertEquals(setOf("tag-all"), captured.captured.keys)
    }

    @Test
    fun `a promotion run keeps only the target environment's jobs and prunes needs to excluded jobs`() = runTest {
        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(
            pipelineId, repoId, releaseDefinition, "abc", "refs/heads/main", PipelineTriggerType.PROMOTION,
            parameters = mapOf("release.version" to "6.2.0", "promotion.environment" to "production"),
        )

        assertEquals(setOf("deploy-production"), captured.captured.keys)
        assertEquals(emptyList(), captured.captured.getValue("deploy-production").needs)
    }

    private val environmentDefinition = PipelineDefinition(
        name = "Env Release",
        triggers = listOf(
            PipelineTrigger(type = PipelineTriggerType.RELEASE),
            PipelineTrigger(type = PipelineTriggerType.PROMOTION, environments = listOf("staging", "production")),
            PipelineTrigger(type = PipelineTriggerType.PUSH),
        ),
        environments = mapOf(
            "staging" to EnvironmentDefinition(deployOnRelease = true),
            "production" to EnvironmentDefinition(promotesFrom = "staging", approval = true),
        ),
        jobs = mapOf(
            "build" to JobDefinition(
                condition = "event == 'release' || event == 'push'",
                steps = listOf(StepDefinition(name = "Build", run = "build")),
            ),
            "record-promotion" to JobDefinition(
                steps = listOf(StepDefinition(name = "Record", run = "record")),
            ),
            "deploy-staging" to JobDefinition(
                environment = "staging",
                needs = listOf("build"),
                steps = listOf(StepDefinition(name = "Deploy", run = "deploy-staging")),
            ),
            "deploy-production" to JobDefinition(
                environment = "production",
                needs = listOf("build", "record-promotion"),
                steps = listOf(StepDefinition(name = "Deploy", run = "deploy-production")),
            ),
        ),
    )

    @Test
    fun `a release run activates deploy-on-release environments and skips the rest`() = runTest {
        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(
            pipelineId, repoId, environmentDefinition, "abc", "refs/tags/v6.2.0", PipelineTriggerType.RELEASE,
        )

        assertEquals(setOf("build", "record-promotion", "deploy-staging"), captured.captured.keys)
        assertEquals(false, captured.captured.getValue("deploy-staging").approval)
    }

    @Test
    fun `a promotion run takes the target's jobs plus needed environment-less jobs and inherits environment approval`() =
        runTest {
            val captured = slot<Map<String, JobDefinition>>()
            coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

            service.createRun(
                pipelineId, repoId, environmentDefinition, "abc", "refs/tags/v6.2.0", PipelineTriggerType.PROMOTION,
                parameters = mapOf("promotion.environment" to "production"),
            )

            // build's condition dropped it; record-promotion rode in on deploy-production's needs.
            assertEquals(setOf("record-promotion", "deploy-production"), captured.captured.keys)
            val deploy = captured.captured.getValue("deploy-production")
            assertEquals(listOf("record-promotion"), deploy.needs)
            assertEquals(true, deploy.approval)
        }

    @Test
    fun `a promotion run excludes environment-less jobs the target does not need`() = runTest {
        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(
            pipelineId, repoId, environmentDefinition, "abc", "refs/tags/v6.2.0", PipelineTriggerType.PROMOTION,
            parameters = mapOf("promotion.environment" to "staging"),
        )

        assertEquals(setOf("deploy-staging"), captured.captured.keys)
        assertEquals(false, captured.captured.getValue("deploy-staging").approval)
    }

    @Test
    fun `a non-release trigger excludes every environment-bound job`() = runTest {
        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(
            pipelineId, repoId, environmentDefinition, "abc", "refs/heads/main", PipelineTriggerType.PUSH,
        )

        assertEquals(setOf("build", "record-promotion"), captured.captured.keys)
    }

    // ─── Promotion chain validation + downgrade guard ───────────────────────────────

    @Test
    fun `a promotion targeting an environment the pipeline does not declare is rejected`() = runTest {
        val definition = environmentDefinition.copy(
            triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PROMOTION)),
        )
        val e = assertFailsWith<IllegalArgumentException> {
            service.createRun(
                pipelineId, repoId, definition, "abc", "refs/tags/v6.2.0", PipelineTriggerType.PROMOTION,
                parameters = mapOf("promotion.environment" to "ghost"),
            )
        }
        assertTrue("environments" in (e.message ?: ""), e.message)
        coVerify(exactly = 0) { runRepository.create(any()) }
    }

    @Test
    fun `a promotion is rejected until its promotes-from stage has succeeded for the ref`() = runTest {
        coEvery { runRepository.findLatestSuccessfulRelease(pipelineId, "refs/tags/v6.2.0") } returns null
        coEvery { runRepository.findLatestSuccessfulPromotion(pipelineId, "refs/tags/v6.2.0", "staging") } returns null

        val e = assertFailsWith<IllegalArgumentException> {
            service.createRun(
                pipelineId, repoId, environmentDefinition, "abc", "refs/tags/v6.2.0", PipelineTriggerType.PROMOTION,
                parameters = mapOf("promotion.environment" to "production"),
            )
        }
        assertTrue("staging" in (e.message ?: ""), e.message)
        coVerify(exactly = 0) { runRepository.create(any()) }

        // A successful promotion INTO staging (release run absent) satisfies the chain.
        coEvery { runRepository.findLatestSuccessfulPromotion(pipelineId, "refs/tags/v6.2.0", "staging") } returns testRun()
        coEvery { runRepository.findLatestSuccessfulPromotionToEnvironment(pipelineId, "production") } returns null
        coEvery { jobService.createJobs(any(), any()) } returns emptyList()
        service.createRun(
            pipelineId, repoId, environmentDefinition, "abc", "refs/tags/v6.2.0", PipelineTriggerType.PROMOTION,
            parameters = mapOf("promotion.environment" to "production"),
        )
        coVerify(exactly = 1) { runRepository.create(any()) }
    }

    @Test
    fun `promoting an older version than the environment holds is blocked unless overridden`() = runTest {
        coEvery { runRepository.findLatestSuccessfulRelease(pipelineId, any()) } returns testRun()
        coEvery { runRepository.findLatestSuccessfulPromotionToEnvironment(pipelineId, "production") } returns
            testRun().copy(
                parameters = kotlinx.serialization.json.buildJsonObject {
                    put("release.version", kotlinx.serialization.json.JsonPrimitive("6.2.0"))
                },
            )
        coEvery { jobService.createJobs(any(), any()) } returns emptyList()

        val e = assertFailsWith<IllegalArgumentException> {
            service.createRun(
                pipelineId, repoId, environmentDefinition, "abc", "refs/tags/v6.1.9", PipelineTriggerType.PROMOTION,
                parameters = mapOf("promotion.environment" to "production", "release.version" to "6.1.9"),
            )
        }
        assertTrue("DOWNGRADE" in (e.message ?: ""), e.message)

        // The explicit override lets it through; a NEWER version needs no override.
        service.createRun(
            pipelineId, repoId, environmentDefinition, "abc", "refs/tags/v6.1.9", PipelineTriggerType.PROMOTION,
            parameters = mapOf(
                "promotion.environment" to "production",
                "release.version" to "6.1.9",
                "promotion.allowDowngrade" to "true",
            ),
        )
        service.createRun(
            pipelineId, repoId, environmentDefinition, "abc", "refs/tags/v6.2.10", PipelineTriggerType.PROMOTION,
            parameters = mapOf("promotion.environment" to "production", "release.version" to "6.2.10"),
        )
        coVerify(exactly = 2) { runRepository.create(any()) }
    }

    @Test
    fun `pipeline-level secrets flatten into every selected job and merge with job-level ones`() = runTest {
        val definition = PipelineDefinition(
            name = "Secrets",
            secrets = listOf("REGISTRY_TOKEN"),
            jobs = mapOf(
                "build" to JobDefinition(steps = listOf(StepDefinition(name = "B", run = "b"))),
                "deploy" to JobDefinition(
                    secrets = listOf("PLAY_SERVICE_ACCOUNT", "REGISTRY_TOKEN"),
                    steps = listOf(StepDefinition(name = "D", run = "d")),
                ),
            ),
        )
        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(pipelineId, repoId, definition, "abc", "refs/heads/main", PipelineTriggerType.PUSH)

        assertEquals(listOf("REGISTRY_TOKEN"), captured.captured.getValue("build").secrets)
        assertEquals(
            listOf("REGISTRY_TOKEN", "PLAY_SERVICE_ACCOUNT"),
            captured.captured.getValue("deploy").secrets,
        )
    }

    @Test
    fun `createRun persists the effective parameters on the run row`() = runTest {
        coEvery { runRepository.findLatestSuccessfulRelease(pipelineId, any()) } returns testRun()
        coEvery { runRepository.findLatestSuccessfulPromotionToEnvironment(pipelineId, any()) } returns null
        coEvery { jobService.createJobs(any(), any()) } returns emptyList()
        val captured = slot<PipelineRun>()
        coEvery { runRepository.create(capture(captured)) } answers { captured.captured.copy(id = UUID.random()) }

        service.createRun(
            pipelineId, repoId, releaseDefinition, "abc", "refs/tags/v6.2.0", PipelineTriggerType.PROMOTION,
            parameters = mapOf("promotion.environment" to "production", "release.version" to "6.2.0"),
        )

        val stored = captured.captured.parameters as kotlinx.serialization.json.JsonObject
        assertEquals("production", (stored.getValue("promotion.environment") as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("6.2.0", (stored.getValue("release.version") as kotlinx.serialization.json.JsonPrimitive).content)
        // Declared input defaults are part of the EFFECTIVE parameters, so they persist too.
        assertEquals("true", (stored.getValue("inputs.phasedRelease") as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `declared input defaults flow into step env as inputs parameters`() = runTest {
        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(
            pipelineId, repoId, releaseDefinition, "abc", "refs/heads/main", PipelineTriggerType.PROMOTION,
            parameters = mapOf("promotion.environment" to "production"),
        )

        val stepEnv = captured.captured.getValue("deploy-production").steps.single().env
        assertEquals("true", stepEnv["inputs.phasedRelease"])
        assertEquals("production", stepEnv["inputs.track"])
    }

    @Test
    fun `a submitted input overrides its default and is type-checked`() = runTest {
        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(
            pipelineId, repoId, releaseDefinition, "abc", "refs/heads/main", PipelineTriggerType.PROMOTION,
            parameters = mapOf("promotion.environment" to "production", "inputs.phasedRelease" to "false"),
        )
        assertEquals("false", captured.captured.getValue("deploy-production").steps.single().env["inputs.phasedRelease"])

        assertFailsWith<IllegalArgumentException> {
            service.createRun(
                pipelineId, repoId, releaseDefinition, "abc", "refs/heads/main", PipelineTriggerType.PROMOTION,
                parameters = mapOf("promotion.environment" to "production", "inputs.phasedRelease" to "maybe"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createRun(
                pipelineId, repoId, releaseDefinition, "abc", "refs/heads/main", PipelineTriggerType.PROMOTION,
                parameters = mapOf("promotion.environment" to "production", "inputs.track" to "alpha"),
            )
        }
    }

    @Test
    fun `an unknown submitted input is rejected`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.createRun(
                pipelineId, repoId, releaseDefinition, "abc", "refs/heads/main", PipelineTriggerType.PROMOTION,
                parameters = mapOf("promotion.environment" to "production", "inputs.bogus" to "x"),
            )
        }
        coVerify(exactly = 0) { runRepository.create(any()) }
    }

    @Test
    fun `a promotion without a target environment or to a disallowed environment is rejected`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.createRun(
                pipelineId, repoId, releaseDefinition, "abc", "refs/heads/main", PipelineTriggerType.PROMOTION,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createRun(
                pipelineId, repoId, releaseDefinition, "abc", "refs/heads/main", PipelineTriggerType.PROMOTION,
                parameters = mapOf("promotion.environment" to "staging"),
            )
        }
        coVerify(exactly = 0) { runRepository.create(any()) }
    }

    @Test
    fun `release scope filters jobs by project membership`() = runTest {
        val scoped = PipelineDefinition(
            name = "Scoped",
            triggers = listOf(PipelineTrigger(type = PipelineTriggerType.RELEASE)),
            jobs = mapOf(
                "tag-server" to JobDefinition(
                    condition = "contains(release.projects, 'server')",
                    steps = listOf(StepDefinition(name = "Tag", run = "tag server")),
                ),
                "tag-web" to JobDefinition(
                    condition = "contains(release.projects, 'web')",
                    steps = listOf(StepDefinition(name = "Tag", run = "tag web")),
                ),
            ),
        )
        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(
            pipelineId, repoId, scoped, "abc", "refs/heads/main", PipelineTriggerType.RELEASE,
            parameters = mapOf("release.projects" to "workspace,server"),
        )

        assertEquals(setOf("tag-server"), captured.captured.keys)
    }

    @Test
    fun `release parameters interpolate in artifact coordinates and requirement refs`() = runTest {
        // The pipeline requirement makes createRun trigger the immediate gate check.
        provides<PipelineRequirementChecker>(singleton = true) { mockk(relaxed = true) }
        val withArtifacts = PipelineDefinition(
            name = "Release",
            triggers = listOf(PipelineTrigger(type = PipelineTriggerType.RELEASE)),
            jobs = mapOf(
                "await" to JobDefinition(
                    steps = listOf(StepDefinition(name = "Await", run = "true")),
                    pipelineRequires = listOf(
                        bosca.git.model.PipelineRequirement(
                            pipeline = "Member Release",
                            repository = "server",
                            ref = "refs/tags/\${{ release.version }}",
                        )
                    ),
                    artifacts = listOf(ArtifactDefinition("docker", "bosca-docker", "server:\${{ release.version }}")),
                ),
            ),
        )
        val captured = slot<Map<String, JobDefinition>>()
        coEvery { jobService.createJobs(any(), capture(captured)) } returns emptyList()

        service.createRun(
            pipelineId, repoId, withArtifacts, "abc", "refs/heads/main", PipelineTriggerType.RELEASE,
            parameters = mapOf("release.version" to "6.2.0"),
        )

        val job = captured.captured.getValue("await")
        assertEquals("server:6.2.0", job.artifacts.single().coordinate)
        assertEquals("refs/tags/6.2.0", job.pipelineRequires.single().ref)
    }

    @Test
    fun `a run where every job is excluded is rejected before any row is created`() = runTest {
        val scoped = PipelineDefinition(
            name = "Scoped",
            triggers = listOf(PipelineTrigger(type = PipelineTriggerType.RELEASE)),
            jobs = mapOf(
                "tag-server" to JobDefinition(
                    condition = "contains(release.projects, 'server')",
                    steps = listOf(StepDefinition(name = "Tag", run = "tag server")),
                ),
            ),
        )

        assertFailsWith<bosca.git.model.PipelinePlanRejectedException> {
            service.createRun(
                pipelineId, repoId, scoped, "abc", "refs/heads/main", PipelineTriggerType.RELEASE,
                parameters = mapOf("release.projects" to "web"),
            )
        }
        coVerify(exactly = 0) { runRepository.create(any()) }
    }

    @Test
    fun `plans that can never run are rejected with a dedicated type`() {
        val environmentOnly = PipelineDefinition(
            name = "Deploy",
            triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH)),
            jobs = mapOf(
                "deploy" to JobDefinition(environment = "production", steps = listOf(StepDefinition(name = "Deploy", run = "deploy"))),
            ),
        )
        assertFailsWith<bosca.git.model.PipelinePlanRejectedException> {
            service.plan(environmentOnly, "refs/heads/main", PipelineTriggerType.PUSH)
        }

        val requiredInput = PipelineDefinition(
            name = "Build",
            triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH, inputs = mapOf("target" to TriggerInput()))),
            jobs = mapOf("build" to JobDefinition(steps = listOf(StepDefinition(name = "Build", run = "build")))),
        )
        val missing = assertFailsWith<bosca.git.model.PipelinePlanRejectedException> {
            service.plan(requiredInput, "refs/heads/main", PipelineTriggerType.PUSH)
        }
        assertTrue(missing.message.orEmpty().contains("Missing required input 'target'"))
    }

    // ── Re-run failed jobs (run control) ──────────────────────────────

    @Test
    fun `cancelJob cancels exactly the selected job and finalizes its run`() = runTest {
        val run = testRun(status = PipelineRunStatus.RUNNING)
        val dispatchId = UUID.random()
        val active = testJob(
            pipelineRunId = run.id,
            status = PipelineRunStatus.RUNNING,
        ).copy(kubernetesDispatchId = dispatchId)
        val cancelled = active.copy(
            status = PipelineRunStatus.CANCELLED,
            errorMessage = "Job cancelled by user",
        )
        val independent = testJob(pipelineRunId = run.id, status = PipelineRunStatus.RUNNING)
        coEvery { jobService.findById(active.id) } returns active andThen cancelled andThen cancelled
        coEvery {
            jobService.finishIfActive(active.id, PipelineRunStatus.CANCELLED, "Job cancelled by user")
        } returns true
        coEvery { runRepository.findById(run.id) } returns run
        coEvery { jobService.findByRun(run.id) } returns listOf(cancelled, independent)

        val result = service.cancelJob(active.id)

        assertEquals(PipelineRunStatus.CANCELLED, result.status)
        coVerify(exactly = 1) {
            jobService.finishIfActive(active.id, PipelineRunStatus.CANCELLED, "Job cancelled by user")
        }
        coVerify(exactly = 1) { kubernetesDispatchService.cancel(dispatchId) }
        coVerify(exactly = 1) { jobService.cancelBlockedJobs(run.id) }
        coVerify(exactly = 0) { jobService.finishIfActive(independent.id, any(), any()) }
    }

    @Test
    fun `cancelJob rejects a terminal job`() = runTest {
        val job = testJob(status = PipelineRunStatus.SUCCESS)
        coEvery { jobService.findById(job.id) } returns job

        assertFailsWith<IllegalStateException> { service.cancelJob(job.id) }

        coVerify(exactly = 0) { jobService.finishIfActive(any(), any(), any()) }
    }

    @Test
    fun `rerunJob resets only the selected job and reopens its run`() = runTest {
        val run = testRun(status = PipelineRunStatus.FAILURE)
        val selected = testJob(
            pipelineRunId = run.id,
            status = PipelineRunStatus.FAILURE,
        )
        val reset = selected.copy(status = PipelineRunStatus.QUEUED, attempt = 2)
        val rerunner = UUID.random()
        coEvery { jobService.findById(selected.id) } returns selected
        coEvery { runRepository.findById(run.id) } returns run
        coEvery { jobService.resetForRerun(selected) } returns reset
        coEvery { runRepository.resetForRerun(run.id, rerunner) } returns
            run.copy(status = PipelineRunStatus.QUEUED, triggeredBy = rerunner)

        val result = service.rerunJob(selected.id, rerunner)

        assertEquals(PipelineRunStatus.QUEUED, result.status)
        assertEquals(2, result.attempt)
        coVerify(exactly = 1) { jobService.resetForRerun(selected) }
        coVerify(exactly = 1) { runRepository.resetForRerun(run.id, rerunner) }
        coVerify(exactly = 0) { jobService.findByRun(run.id) }
    }

    @Test
    fun `rerunJob rejects a job whose run is still active`() = runTest {
        val run = testRun(status = PipelineRunStatus.RUNNING)
        val failed = testJob(pipelineRunId = run.id, status = PipelineRunStatus.FAILURE)
        coEvery { jobService.findById(failed.id) } returns failed
        coEvery { runRepository.findById(run.id) } returns run

        assertFailsWith<IllegalStateException> { service.rerunJob(failed.id) }

        coVerify(exactly = 0) { jobService.resetForRerun(any()) }
    }

    @Test
    fun `runJobAnyway opens only the external gate of a queued job`() = runTest {
        val run = testRun(status = PipelineRunStatus.RUNNING)
        val actor = UUID.random()
        val waiting = testJob(
            pipelineRunId = run.id,
            status = PipelineRunStatus.QUEUED,
        ).copy(
            dependsOn = listOf("compile"),
            pipelineRequirements = Json.parseToJsonElement(
                """[{"repository":"bosca","pipeline":"build"}]"""
            ),
        )
        val bypassed = waiting.copy(
            requirementsSatisfiedAt = OffsetDateTime.now(),
            requirementsBypassedAt = OffsetDateTime.now(),
            requirementsBypassedBy = actor,
        )
        coEvery { jobService.findById(waiting.id) } returnsMany listOf(waiting, bypassed)
        coEvery { runRepository.findById(run.id) } returns run
        coEvery { jobService.bypassRequirements(waiting.id, actor, "urgent") } returns bypassed
        coEvery { jobService.completeGateJobs(run.id) } returns emptyList()

        val result = service.runJobAnyway(waiting.id, actor, "urgent")

        assertEquals(bypassed, result)
        assertEquals(listOf("compile"), result.dependsOn)
        coVerify(exactly = 1) { jobService.bypassRequirements(waiting.id, actor, "urgent") }
        coVerify(exactly = 0) { jobService.resetForRerun(any()) }
        coVerify(exactly = 0) { runRepository.resetForRerun(any(), any()) }
    }

    @Test
    fun `runJobAnyway reopens a terminal requirement failure before bypassing it`() = runTest {
        val run = testRun(status = PipelineRunStatus.FAILURE)
        val actor = UUID.random()
        val failed = testJob(
            pipelineRunId = run.id,
            status = PipelineRunStatus.FAILURE,
        ).copy(
            pipelineRequirements = Json.parseToJsonElement(
                """[{"repository":"bosca","pipeline":"build"}]"""
            ),
        )
        val reset = failed.copy(status = PipelineRunStatus.QUEUED, attempt = 2, finished = null)
        val bypassed = reset.copy(
            requirementsSatisfiedAt = OffsetDateTime.now(),
            requirementsBypassedAt = OffsetDateTime.now(),
            requirementsBypassedBy = actor,
        )
        coEvery { jobService.findById(failed.id) } returnsMany listOf(failed, bypassed)
        coEvery { runRepository.findById(run.id) } returns run
        coEvery { jobService.resetForRerun(failed) } returns reset
        coEvery { runRepository.resetForRerun(run.id, actor) } returns
            run.copy(status = PipelineRunStatus.QUEUED, triggeredBy = actor, finished = null)
        coEvery { jobService.bypassRequirements(failed.id, actor, null) } returns bypassed
        coEvery { jobService.completeGateJobs(run.id) } returns emptyList()

        val result = service.runJobAnyway(failed.id, actor)

        assertEquals(2, result.attempt)
        assertEquals(actor, result.requirementsBypassedBy)
        coVerify(exactly = 1) { jobService.resetForRerun(failed) }
        coVerify(exactly = 1) { runRepository.resetForRerun(run.id, actor) }
        coVerify(exactly = 1) { jobService.bypassRequirements(failed.id, actor, null) }
    }

    @Test
    fun `rerunFailedJobs rejects a run that is not terminal-failed`() = runTest {
        val run = testRun(status = PipelineRunStatus.RUNNING)
        coEvery { runRepository.findById(run.id) } returns run

        assertFailsWith<IllegalStateException> { service.rerunFailedJobs(run.id) }
        coVerify(exactly = 0) { jobService.resetForRerun(any()) }
    }

    @Test
    fun `rerunFailedJobs rejects a run with no failed or cancelled jobs`() = runTest {
        val run = testRun(status = PipelineRunStatus.FAILURE)
        coEvery { runRepository.findById(run.id) } returns run
        coEvery { jobService.findByRun(run.id) } returns listOf(testJob(status = PipelineRunStatus.SUCCESS))

        assertFailsWith<IllegalStateException> { service.rerunFailedJobs(run.id) }
        coVerify(exactly = 0) { runRepository.resetForRerun(any(), any()) }
    }

    @Test
    fun `rerunFailedJobs resets only failed and cancelled jobs and reopens the run`() = runTest {
        val run = testRun(status = PipelineRunStatus.FAILURE)
        val succeeded = testJob(status = PipelineRunStatus.SUCCESS)
        val failed = testJob(status = PipelineRunStatus.FAILURE)
        val cancelled = testJob(status = PipelineRunStatus.CANCELLED)
        coEvery { runRepository.findById(run.id) } returns run
        coEvery { jobService.findByRun(run.id) } returns listOf(succeeded, failed, cancelled)
        coEvery { jobService.resetForRerun(any()) } answers {
            (firstArg() as PipelineJob).copy(status = PipelineRunStatus.QUEUED, attempt = 2)
        }
        val rerunner = UUID.random()
        coEvery { runRepository.resetForRerun(run.id, rerunner) } returns run.copy(status = PipelineRunStatus.QUEUED)

        val reopened = service.rerunFailedJobs(run.id, rerunner)

        assertEquals(PipelineRunStatus.QUEUED, reopened.status)
        coVerify(exactly = 1) { jobService.resetForRerun(failed) }
        coVerify(exactly = 1) { jobService.resetForRerun(cancelled) }
        coVerify(exactly = 0) { jobService.resetForRerun(succeeded) }
        coVerify(exactly = 1) { runRepository.resetForRerun(run.id, rerunner) }
    }

    @Test
    fun `rerunFailedJobs retires the ephemeral agent from the previous attempt`() = runTest {
        val run = testRun(status = PipelineRunStatus.FAILURE)
        val agentId = UUID.random()
        val dispatchId = UUID.random()
        val failed = testJob(status = PipelineRunStatus.FAILURE).copy(
            agentId = agentId,
            kubernetesDispatchId = dispatchId,
        )
        val agent = PipelineAgent(
            id = agentId,
            name = "kubernetes-old-attempt",
            ephemeral = true,
            jobId = failed.id,
            tokenHash = "unused",
        )
        coEvery { runRepository.findById(run.id) } returns run
        coEvery { jobService.findByRun(run.id) } returns listOf(failed)
        coEvery { jobService.resetForRerun(failed) } returns failed.copy(
            status = PipelineRunStatus.QUEUED,
            agentId = null,
            kubernetesDispatchId = null,
            attempt = 2,
        )
        coEvery { agentService.findById(agentId) } returns agent
        coEvery { runRepository.resetForRerun(run.id, null) } returns
            run.copy(status = PipelineRunStatus.QUEUED)

        service.rerunFailedJobs(run.id)

        coVerify(exactly = 1) { kubernetesDispatchService.cancel(dispatchId) }
        coVerify(exactly = 1) { agentService.deregister(agentId) }
    }

    @Test
    fun `rerunFailedJobs re-evaluates a still-gated reset job`() = runTest {
        val checker = mockk<PipelineRequirementChecker>(relaxed = true)
        provides<PipelineRequirementChecker>(singleton = true) { checker }
        val run = testRun(status = PipelineRunStatus.FAILURE)
        val gated = testJob(status = PipelineRunStatus.FAILURE).copy(
            pipelineRequirements = Json.parseToJsonElement("""[{"pipeline":"P","repository":"r"}]"""),
        )
        coEvery { runRepository.findById(run.id) } returns run
        coEvery { jobService.findByRun(run.id) } returns listOf(gated)
        coEvery { jobService.resetForRerun(any()) } answers {
            (firstArg() as PipelineJob).copy(status = PipelineRunStatus.QUEUED, attempt = 2)
        }
        coEvery { runRepository.resetForRerun(run.id, null) } returns run.copy(status = PipelineRunStatus.QUEUED)

        service.rerunFailedJobs(run.id)

        coVerify(exactly = 1) { checker.checkRun(run.id) }
    }

    @Test
    fun `rerunFailedJobs re-evaluates reset conditional jobs`() = runTest {
        val run = testRun(status = PipelineRunStatus.FAILURE)
        val conditional = testJob(status = PipelineRunStatus.FAILURE).copy(
            condition = "failure()",
            conditionSatisfiedAt = OffsetDateTime.now(),
        )
        coEvery { runRepository.findById(run.id) } returns run
        coEvery { jobService.findByRun(run.id) } returns listOf(conditional)
        coEvery { jobService.resetForRerun(conditional) } returns conditional.copy(
            status = PipelineRunStatus.QUEUED,
            conditionSatisfiedAt = null,
        )
        coEvery { runRepository.resetForRerun(run.id, null) } returns run.copy(status = PipelineRunStatus.QUEUED)

        service.rerunFailedJobs(run.id)

        coVerify(exactly = 1) { jobService.evaluateDeferredConditions(run.id) }
    }

    private fun testRun(
        id: UUID = UUID.random(),
        status: PipelineRunStatus = PipelineRunStatus.QUEUED,
        concurrencyGroup: String? = null
    ) = PipelineRun(
        id = id,
        pipelineId = pipelineId,
        repositoryId = repoId,
        commitSha = "abc123",
        ref = "refs/heads/main",
        triggerType = PipelineTriggerType.PUSH,
        status = status,
        number = 1,
        concurrencyGroup = concurrencyGroup
    )

    private fun testJob(
        pipelineRunId: UUID = UUID.random(),
        status: PipelineRunStatus = PipelineRunStatus.QUEUED
    ) = PipelineJob(
        id = UUID.random(),
        pipelineRunId = pipelineRunId,
        name = "build",
        status = status,
        runnerLabel = "linux"
    )
}

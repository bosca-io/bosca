package bosca.git.ci.service

import bosca.di.asProvider
import bosca.di.ObjectProvider
import bosca.git.ci.configuration.KubernetesCiDispatchConfiguration
import bosca.git.model.PipelineAgent
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineTriggerType
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.model.KubernetesJobResult
import bosca.kubernetes.model.KubernetesJobResultStatus
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass

class KubernetesCiDispatcherTest {

    private val agentService = mockk<PipelineAgentService>()
    private val jobService = mockk<PipelineJobService>(relaxed = true)
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val dispatchService = mockk<KubernetesJobDispatchService>()

    @Test
    fun `dispatch creates a scoped agent and reserves the attempt`() = runTest {
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "android-build",
            runnerLabel = "android",
        )
        val agent = PipelineAgent(
            id = UUID.random(),
            name = "kubernetes-agent",
            labels = listOf("android"),
            ephemeral = true,
            jobId = job.id,
            tokenHash = "unused",
        )
        val dispatchId = UUID.random()
        val request = slot<KubernetesJobRequest>()
        val principalId = UUID.random()

        coEvery {
            jobService.findNextKubernetesDispatchCandidate(listOf("android"))
        } returns job andThen null
        coEvery {
            agentService.registerKubernetesEphemeral(
                job.id,
                any(),
                listOf("android"),
                1_510,
                principalId,
            )
        } returns (agent to "bsk_job_token")
        coEvery { runService.findById(job.pipelineRunId) } returns PipelineRun(
            id = job.pipelineRunId,
            pipelineId = UUID.random(),
            repositoryId = UUID.random(),
            commitSha = "abc",
            ref = "refs/heads/main",
            triggerType = PipelineTriggerType.PUSH,
            triggeredBy = principalId,
        )
        coEvery { dispatchService.dispatch(capture(request)) } returns dispatchId
        coEvery {
            jobService.markKubernetesDispatched(job.id, dispatchId, agent.id)
        } returns job.copy(agentId = agent.id, kubernetesDispatchId = dispatchId)

        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration(setOf("android")),
        )

        assertEquals(1, dispatcher.dispatchAvailable())
        assertEquals("android", request.captured.profile)
        assertEquals(job.id.toString(), request.captured.environment["BOSCA_CI_JOB_ID"])
        assertEquals(agent.id.toString(), request.captured.environment["BOSCA_CI_AGENT_ID"])
        assertEquals("bsk_job_token", request.captured.environment["BOSCA_TOKEN"])
        assertEquals("ci-${job.id}-attempt-1", request.captured.idempotencyKey)
    }

    @Test
    fun `disabled dispatch leaves polling agents in control`() = runTest {
        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration.disabled,
        )

        assertEquals(0, dispatcher.dispatchAvailable())
        coVerify(exactly = 0) { jobService.findNextKubernetesDispatchCandidate(any()) }
        coVerify(exactly = 0) { dispatchService.dispatch(any()) }
    }

    @Test
    fun `missing generic Kubernetes service disables dispatch and reconciliation`() = runTest {
        val unavailable = object : ObjectProvider<KubernetesJobDispatchService> {
            override val type: KClass<KubernetesJobDispatchService> =
                KubernetesJobDispatchService::class
            override val exists: Boolean = false
            override suspend fun get(): KubernetesJobDispatchService =
                error("unavailable provider must not be resolved")
        }
        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            unavailable,
            KubernetesCiDispatchConfiguration(setOf("android")),
        )

        assertEquals(false, dispatcher.enabled)
        assertEquals(0, dispatcher.dispatchAvailable())
        assertEquals(0, dispatcher.reconcileExecutions())
        coVerify(exactly = 0) { jobService.findNextKubernetesDispatchCandidate(any()) }
        coVerify(exactly = 0) { jobService.findUnfinalizedKubernetesDispatched(any()) }
    }

    @Test
    fun `dispatch fails unattributed pipeline work without creating a workload identity`() = runTest {
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "android-build",
            runnerLabel = "android",
        )
        coEvery {
            jobService.findNextKubernetesDispatchCandidate(listOf("android"))
        } returns job andThen null
        coEvery { runService.findById(job.pipelineRunId) } returns null
        coEvery {
            jobService.finishIfActive(
                job.id,
                PipelineRunStatus.FAILURE,
                "Kubernetes CI requires the pipeline run to retain its initiating principal",
            )
        } returns true

        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration(setOf("android")),
        )

        assertEquals(1, dispatcher.dispatchAvailable())
        coVerify(exactly = 0) { agentService.registerKubernetesEphemeral(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { dispatchService.dispatch(any()) }
        coVerify(exactly = 0) { jobService.markKubernetesDispatched(any(), any(), any()) }
    }

    @Test
    fun `dispatch drains a bounded sequence and honors an explicit job timeout`() = runTest {
        val runId = UUID.random()
        val principalId = UUID.random()
        val jobs = listOf(
            PipelineJob(
                id = UUID.random(),
                pipelineRunId = runId,
                name = "first",
                runnerLabel = "android",
                timeoutMinutes = 5,
            ),
            PipelineJob(
                id = UUID.random(),
                pipelineRunId = runId,
                name = "second",
                runnerLabel = "android",
                timeoutMinutes = 10,
            ),
        )
        val run = PipelineRun(
            id = runId,
            pipelineId = UUID.random(),
            repositoryId = UUID.random(),
            commitSha = "abc",
            ref = "refs/heads/main",
            triggerType = PipelineTriggerType.PUSH,
            triggeredBy = principalId,
        )
        coEvery {
            jobService.findNextKubernetesDispatchCandidate(listOf("android"))
        } returnsMany (jobs + null)
        coEvery { runService.findById(runId) } returns run
        jobs.forEach { job ->
            val agent = PipelineAgent(
                id = UUID.random(),
                name = "agent",
                labels = listOf("android"),
                ephemeral = true,
                jobId = job.id,
                tokenHash = "unused",
            )
            coEvery {
                agentService.registerKubernetesEphemeral(
                    job.id,
                    any(),
                    listOf("android"),
                    requireNotNull(job.timeoutMinutes).toLong() + 1_450,
                    principalId,
                )
            } returns (agent to "token-${job.id}")
            coEvery { dispatchService.dispatch(any()) } returns UUID.random()
            coEvery {
                jobService.markKubernetesDispatched(job.id, any(), agent.id)
            } returns job.copy(agentId = agent.id, kubernetesDispatchId = UUID.random())
        }

        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration(setOf("android")),
        )

        assertEquals(2, dispatcher.dispatchAvailable())
        coVerify(exactly = 2) { dispatchService.dispatch(any()) }
    }

    @Test
    fun `durable kubernetes startup failure fails the ci job`() = runTest {
        val dispatchId = UUID.random()
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "android-build",
            runnerLabel = "android",
            kubernetesDispatchId = dispatchId,
        )
        val failed = job.copy(
            status = PipelineRunStatus.FAILURE,
            errorMessage = "Pod agent failed to start: ImagePullBackOff",
        )
        coEvery { jobService.findUnfinalizedKubernetesDispatched(1_000) } returns listOf(job)
        coEvery { jobService.claimKubernetesFinalization(job.id) } returns job
        coEvery { dispatchService.getResult(dispatchId) } returns KubernetesJobResult(
            dispatchId = dispatchId,
            profile = "android",
            idempotencyKey = "ci-${job.id}-attempt-1",
            status = KubernetesJobResultStatus.FAILED,
            message = failed.errorMessage,
            finishedAt = OffsetDateTime.now(),
        )
        coEvery {
            jobService.finishIfActive(job.id, PipelineRunStatus.FAILURE, failed.errorMessage)
        } returns true
        coEvery { jobService.findById(job.id) } returns failed

        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration(setOf("android")),
        )

        assertEquals(1, dispatcher.reconcileExecutions())
        coVerify(exactly = 1) {
            jobService.finishIfActive(job.id, PipelineRunStatus.FAILURE, failed.errorMessage)
        }
        coVerify(exactly = 1) {
            dispatchService.getResult(dispatchId)
        }
        coVerify(exactly = 1) { jobService.claimKubernetesFinalization(job.id) }
    }

    @Test
    fun `durable kubernetes success fails an active ci job when the agent report was lost`() = runTest {
        val dispatchId = UUID.random()
        val missingReport = "Kubernetes Job exited without the CI agent reporting a terminal job status"
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "android-build",
            runnerLabel = "android",
            status = PipelineRunStatus.RUNNING,
            kubernetesDispatchId = dispatchId,
        )
        val failed = job.copy(status = PipelineRunStatus.FAILURE, errorMessage = missingReport)
        coEvery { jobService.findUnfinalizedKubernetesDispatched(1_000) } returns listOf(job)
        coEvery { jobService.claimKubernetesFinalization(job.id) } returns job
        coEvery { dispatchService.getResult(dispatchId) } returns KubernetesJobResult(
            dispatchId = dispatchId,
            profile = "android",
            idempotencyKey = "ci-${job.id}-attempt-1",
            status = KubernetesJobResultStatus.SUCCEEDED,
            finishedAt = OffsetDateTime.now(),
        )
        coEvery {
            jobService.finishIfActive(job.id, PipelineRunStatus.FAILURE, missingReport)
        } returns true
        coEvery { jobService.findById(job.id) } returns failed

        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration(setOf("android")),
        )

        assertEquals(1, dispatcher.reconcileExecutions())
        coVerify(exactly = 1) {
            jobService.finishIfActive(job.id, PipelineRunStatus.FAILURE, missingReport)
        }
        coVerify(exactly = 1) { jobService.claimKubernetesFinalization(job.id) }
    }

    @Test
    fun `durable kubernetes success preserves success already reported by the agent`() = runTest {
        val dispatchId = UUID.random()
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "android-build",
            runnerLabel = "android",
            status = PipelineRunStatus.SUCCESS,
            kubernetesDispatchId = dispatchId,
        )
        val missingReport = "Kubernetes Job exited without the CI agent reporting a terminal job status"
        coEvery { jobService.findUnfinalizedKubernetesDispatched(1_000) } returns listOf(job)
        coEvery { jobService.claimKubernetesFinalization(job.id) } returns job
        coEvery { dispatchService.getResult(dispatchId) } returns KubernetesJobResult(
            dispatchId = dispatchId,
            profile = "android",
            idempotencyKey = "ci-${job.id}-attempt-1",
            status = KubernetesJobResultStatus.SUCCEEDED,
            finishedAt = OffsetDateTime.now(),
        )
        coEvery {
            jobService.finishIfActive(job.id, PipelineRunStatus.FAILURE, missingReport)
        } returns false
        coEvery { jobService.findById(job.id) } returns job

        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration(setOf("android")),
        )

        assertEquals(1, dispatcher.reconcileExecutions())
        coVerify(exactly = 1) {
            jobService.finishIfActive(job.id, PipelineRunStatus.FAILURE, missingReport)
        }
    }

    @Test
    fun `a terminal result claimed by another replica has no duplicate side effects`() = runTest {
        val dispatchId = UUID.random()
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "android-build",
            runnerLabel = "android",
            kubernetesDispatchId = dispatchId,
        )
        coEvery { jobService.findUnfinalizedKubernetesDispatched(1_000) } returns listOf(job)
        coEvery { dispatchService.getResult(dispatchId) } returns KubernetesJobResult(
            dispatchId = dispatchId,
            profile = "android",
            idempotencyKey = "ci-${job.id}-attempt-1",
            status = KubernetesJobResultStatus.SUCCEEDED,
            finishedAt = OffsetDateTime.now(),
        )
        coEvery { jobService.claimKubernetesFinalization(job.id) } returns null

        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration(setOf("android")),
        )

        assertEquals(0, dispatcher.reconcileExecutions())
        coVerify(exactly = 0) { jobService.finishIfActive(any(), any(), any()) }
        coVerify(exactly = 0) { jobService.findById(any()) }
    }

    @Test
    fun `cancelled Kubernetes result cancels active CI work with a stable fallback message`() = runTest {
        val dispatchId = UUID.random()
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "android-build",
            runnerLabel = "android",
            kubernetesDispatchId = dispatchId,
        )
        val settled = job.copy(
            status = PipelineRunStatus.CANCELLED,
            errorMessage = "Kubernetes Job was cancelled",
        )
        coEvery { jobService.findUnfinalizedKubernetesDispatched(1_000) } returns listOf(job)
        coEvery { dispatchService.getResult(dispatchId) } returns KubernetesJobResult(
            dispatchId = dispatchId,
            profile = "android",
            idempotencyKey = "ci-${job.id}-attempt-1",
            status = KubernetesJobResultStatus.CANCELLED,
            finishedAt = OffsetDateTime.now(),
        )
        coEvery { jobService.claimKubernetesFinalization(job.id) } returns job
        coEvery {
            jobService.finishIfActive(
                job.id,
                PipelineRunStatus.CANCELLED,
                "Kubernetes Job was cancelled",
            )
        } returns true
        coEvery { jobService.findById(job.id) } returns settled

        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration(setOf("android")),
        )

        assertEquals(1, dispatcher.reconcileExecutions())
        coVerify(exactly = 1) {
            jobService.finishIfActive(
                job.id,
                PipelineRunStatus.CANCELLED,
                "Kubernetes Job was cancelled",
            )
        }
    }

    @Test
    fun `reconciliation skips legacy and active dispatch records`() = runTest {
        val activeDispatchId = UUID.random()
        val legacy = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "legacy",
            runnerLabel = "android",
        )
        val active = legacy.copy(id = UUID.random(), kubernetesDispatchId = activeDispatchId)
        coEvery {
            jobService.findUnfinalizedKubernetesDispatched(1_000)
        } returns listOf(legacy, active)
        coEvery { dispatchService.getResult(activeDispatchId) } returns null

        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration(setOf("android")),
        )

        assertEquals(0, dispatcher.reconcileExecutions())
        coVerify(exactly = 0) { jobService.claimKubernetesFinalization(any()) }
    }

    @Test
    fun `configuration rejects labels that cannot name a Kubernetes profile`() {
        assertFailsWith<IllegalArgumentException> {
            KubernetesCiDispatchConfiguration(setOf("Android Builds"))
        }
    }

    @Test
    fun `failed Kubernetes result uses a stable message when the platform has none`() = runTest {
        val dispatchId = UUID.random()
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "training",
            runnerLabel = "gpu",
            kubernetesDispatchId = dispatchId,
        )
        val settled = job.copy(
            status = PipelineRunStatus.FAILURE,
            errorMessage = "Kubernetes Job failed",
        )
        coEvery { jobService.findUnfinalizedKubernetesDispatched(1_000) } returns listOf(job)
        coEvery { dispatchService.getResult(dispatchId) } returns KubernetesJobResult(
            dispatchId = dispatchId,
            profile = "gpu",
            idempotencyKey = "training-1",
            status = KubernetesJobResultStatus.FAILED,
            finishedAt = OffsetDateTime.now(),
        )
        coEvery { jobService.claimKubernetesFinalization(job.id) } returns job
        coEvery {
            jobService.finishIfActive(job.id, PipelineRunStatus.FAILURE, "Kubernetes Job failed")
        } returns true
        coEvery { jobService.findById(job.id) } returns settled

        val reconciled = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration(setOf("gpu")),
        ).reconcileExecutions()

        assertEquals(1, reconciled)
        coVerify(exactly = 1) {
            jobService.finishIfActive(job.id, PipelineRunStatus.FAILURE, "Kubernetes Job failed")
        }
    }

    @Test
    fun `terminal finalization rejects a disappeared or still active CI job`() = runTest {
        val dispatchId = UUID.random()
        val missing = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "missing",
            runnerLabel = "gpu",
            kubernetesDispatchId = dispatchId,
        )
        coEvery { jobService.findUnfinalizedKubernetesDispatched(1_000) } returns listOf(missing)
        coEvery { dispatchService.getResult(dispatchId) } returns KubernetesJobResult(
            dispatchId = dispatchId,
            profile = "gpu",
            idempotencyKey = "missing",
            status = KubernetesJobResultStatus.FAILED,
            finishedAt = OffsetDateTime.now(),
        )
        coEvery { jobService.claimKubernetesFinalization(missing.id) } returns missing
        coEvery { jobService.findById(missing.id) } returns null
        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration(setOf("gpu")),
        )

        assertFailsWith<IllegalArgumentException> { dispatcher.reconcileExecutions() }

        val active = missing.copy(id = UUID.random())
        coEvery { jobService.findUnfinalizedKubernetesDispatched(1_000) } returns listOf(active)
        coEvery { jobService.claimKubernetesFinalization(active.id) } returns active
        coEvery { jobService.findById(active.id) } returns active.copy(
            status = PipelineRunStatus.RUNNING,
        )
        assertFailsWith<IllegalStateException> { dispatcher.reconcileExecutions() }
    }

    @Test
    fun `dispatch aborts when the CI reservation changes before recording Kubernetes identity`() = runTest {
        val principalId = UUID.random()
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "android",
            runnerLabel = "android",
        )
        val agent = PipelineAgent(
            id = UUID.random(),
            name = "agent",
            labels = listOf("android"),
            ephemeral = true,
            jobId = job.id,
            tokenHash = "unused",
        )
        coEvery { jobService.findNextKubernetesDispatchCandidate(listOf("android")) } returns job
        coEvery { runService.findById(job.pipelineRunId) } returns PipelineRun(
            id = job.pipelineRunId,
            pipelineId = UUID.random(),
            repositoryId = UUID.random(),
            commitSha = "abc",
            ref = "refs/heads/main",
            triggerType = PipelineTriggerType.PUSH,
            triggeredBy = principalId,
        )
        coEvery {
            agentService.registerKubernetesEphemeral(
                job.id,
                any(),
                listOf("android"),
                any(),
                principalId,
            )
        } returns (agent to "token")
        coEvery { dispatchService.dispatch(any()) } returns UUID.random()
        coEvery { jobService.markKubernetesDispatched(job.id, any(), agent.id) } returns null

        val dispatcher = KubernetesCiDispatcher(
            agentService,
            jobService,
            runService,
            dispatchService.asProvider(),
            KubernetesCiDispatchConfiguration(setOf("android")),
        )

        assertFailsWith<IllegalStateException> { dispatcher.dispatchAvailable() }
    }
}

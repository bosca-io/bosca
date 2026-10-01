package bosca.git.ci.service

import bosca.db.connectionOrNull
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.git.ci.configuration.KubernetesCiDispatchConfiguration
import bosca.git.model.PipelineRunStatus
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.model.KubernetesJobResultStatus
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.serialization.UUID
import org.slf4j.LoggerFactory

/**
 * Adapts claimable CI jobs to the generic `kubernetes-jobs` queue.
 *
 * Each candidate is reserved in its own transaction. [KubernetesJobDispatchService] follows the
 * platform queue contract and publishes after that transaction commits. This keeps database locks
 * short, prevents one large backlog from joining an unrelated pipeline transaction, and lets
 * concurrent application instances divide work through `FOR UPDATE SKIP LOCKED`.
 */
class KubernetesCiDispatcher(
    private val agentService: PipelineAgentService,
    private val jobService: PipelineJobService,
    private val runService: PipelineRunService,
    private val kubernetesJobDispatchService: ObjectProvider<KubernetesJobDispatchService>,
    private val configuration: KubernetesCiDispatchConfiguration,
) {

    val enabled: Boolean
        get() = configuration.enabled && kubernetesJobDispatchService.exists

    /**
     * Dispatches a bounded batch of eligible jobs, or returns zero when Kubernetes CI is disabled.
     */
    suspend fun dispatchAvailable(): Int {
        if (!enabled) return 0

        val dispatcher = kubernetesJobDispatchService.get()
        val profiles = configuration.profiles.sorted()
        var dispatched = 0
        while (dispatched < MAX_DISPATCH_BATCH) {
            val found = if (connectionOrNull() == null) {
                // Unit tests use service doubles without installing a database context.
                dispatchNext(profiles, dispatcher)
            } else {
                transaction { dispatchNext(profiles, dispatcher) }
            }
            if (!found) break
            dispatched++
        }
        return dispatched
    }

    /**
     * Applies durable Kubernetes terminal outcomes to their owning CI jobs.
     *
     * This polling path is deliberate: lifecycle delivery remains correct across process restarts
     * and does not depend on a transient pub/sub notification. Terminal CI guards make the sweep
     * race-safe with an agent reporting completion at the same time.
     */
    suspend fun reconcileExecutions(): Int {
        if (!enabled) return 0
        val dispatcher = kubernetesJobDispatchService.get()
        val finalizer = PipelineRunFinalizer(jobService, runService, agentService)
        var reconciled = 0
        for (job in jobService.findUnfinalizedKubernetesDispatched(MAX_LIFECYCLE_BATCH)) {
            val dispatchId = job.kubernetesDispatchId ?: continue
            val result = dispatcher.getResult(dispatchId) ?: continue
            val (status, message) = when (result.status) {
                // A zero pod exit code proves only that the agent process returned normally. The
                // agent owns the logical CI result and must report it before exiting. If the CI
                // job is already terminal, finishIfActive preserves that result; if it is still
                // active, treating the missing report as success would silently pass failed work.
                KubernetesJobResultStatus.SUCCEEDED -> PipelineRunStatus.FAILURE to
                    MISSING_AGENT_TERMINAL_STATUS
                KubernetesJobResultStatus.FAILED -> PipelineRunStatus.FAILURE to
                    (result.message ?: "Kubernetes Job failed")
                KubernetesJobResultStatus.CANCELLED -> PipelineRunStatus.CANCELLED to
                    (result.message ?: "Kubernetes Job was cancelled")
            }
            val applied = if (connectionOrNull() == null) {
                finalizeKubernetesResult(job.id, status, message, finalizer)
            } else {
                transaction {
                    finalizeKubernetesResult(job.id, status, message, finalizer)
                }
            }
            if (applied) {
                reconciled++
                log.info(
                    "Applied Kubernetes dispatch {} status {} to CI job {}: {}",
                    dispatchId,
                    result.status,
                    job.id,
                    message,
                )
            }
        }
        return reconciled
    }

    /**
     * The CI job transition, run finalization, ephemeral-agent removal, and recovery marker share
     * one database transaction. If any step fails, the marker remains null and the sweep retries.
     */
    private suspend fun finalizeKubernetesResult(
        jobId: UUID,
        status: PipelineRunStatus,
        message: String?,
        finalizer: PipelineRunFinalizer,
    ): Boolean {
        if (jobService.claimKubernetesFinalization(jobId) == null) return false
        jobService.finishIfActive(jobId, status, message)
        val settled = requireNotNull(jobService.findById(jobId)) {
            "Kubernetes-dispatched CI job $jobId disappeared during terminal finalization"
        }
        check(settled.status in TERMINAL_STATUSES) {
            "Kubernetes-dispatched CI job $jobId remained ${settled.status} after terminal finalization"
        }
        finalizer.finalizeJob(jobId, settled.status)
        return true
    }

    private suspend fun dispatchNext(
        profiles: List<String>,
        dispatcher: KubernetesJobDispatchService,
    ): Boolean {
        val job = jobService.findNextKubernetesDispatchCandidate(profiles) ?: return false
        val run = runService.findById(job.pipelineRunId)
        val principalId = run?.triggeredBy
        if (principalId == null) {
            val message = "Kubernetes CI requires the pipeline run to retain its initiating principal"
            jobService.finishIfActive(job.id, PipelineRunStatus.FAILURE, message)
            PipelineRunFinalizer(jobService, runService, agentService)
                .finalizeJob(job.id, PipelineRunStatus.FAILURE, releaseAgent = false)
            log.error("Cannot dispatch CI job {}: {}", job.id, message)
            return true
        }
        val (agent, token) = agentService.registerKubernetesEphemeral(
            jobId = job.id,
            name = "kubernetes-${job.id.toString().take(8)}-${UUID.random().toString().take(8)}",
            labels = listOf(job.runnerLabel),
            lifetimeMinutes =
                (job.timeoutMinutes ?: DEFAULT_JOB_TIMEOUT_MINUTES).toLong() +
                    KubernetesJobRequest.DEFAULT_DISPATCH_WAIT_TIMEOUT_SECONDS / 60 +
                    KUBERNETES_STARTUP_ALLOWANCE_MINUTES,
            principalId = principalId,
        )
        val dispatchId = dispatcher.dispatch(
            KubernetesJobRequest(
                profile = job.runnerLabel,
                idempotencyKey = "ci-${job.id}-attempt-${job.attempt}",
                environment = mapOf(
                    CI_JOB_ID_ENV to job.id.toString(),
                    CI_AGENT_ID_ENV to agent.id.toString(),
                    BOSCA_TOKEN_ENV to token,
                ),
                labels = mapOf(CI_JOB_LABEL to job.id.toString()),
            )
        )
        check(jobService.markKubernetesDispatched(job.id, dispatchId, agent.id) != null) {
            "Pipeline job ${job.id} changed while its Kubernetes dispatch was being recorded"
        }
        log.info(
            "Dispatched CI job {} ({}) to Kubernetes profile {} as queue item {}",
            job.id,
            job.name,
            job.runnerLabel,
            dispatchId,
        )
        return true
    }

    companion object {
        private val log = LoggerFactory.getLogger(KubernetesCiDispatcher::class.java)
        private const val MAX_DISPATCH_BATCH = 100
        private const val MAX_LIFECYCLE_BATCH = 1_000
        private const val CI_JOB_ID_ENV = "BOSCA_CI_JOB_ID"
        private const val CI_AGENT_ID_ENV = "BOSCA_CI_AGENT_ID"
        private const val CI_JOB_LABEL = "ci.bosca.io/job-id"
        private const val BOSCA_TOKEN_ENV = "BOSCA_TOKEN"
        private const val DEFAULT_JOB_TIMEOUT_MINUTES = 60
        private const val KUBERNETES_STARTUP_ALLOWANCE_MINUTES = 10L
        private const val MISSING_AGENT_TERMINAL_STATUS =
            "Kubernetes Job exited without the CI agent reporting a terminal job status"
        private val TERMINAL_STATUSES = setOf(
            PipelineRunStatus.SUCCESS,
            PipelineRunStatus.FAILURE,
            PipelineRunStatus.CANCELLED,
            PipelineRunStatus.SKIPPED,
        )
    }
}

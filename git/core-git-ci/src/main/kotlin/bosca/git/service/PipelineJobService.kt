package bosca.git.service

import bosca.git.model.JobDefinition
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineStep
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages pipeline jobs and steps within a run. Handles job creation
 * from definitions (including matrix expansion), agent assignment,
 * status updates, and dependency-based dispatch.
 */
interface PipelineJobService : Service {

    /**
     * Creates jobs and their steps from a map of job definitions.
     * Expands matrix configurations into individual jobs.
     */
    suspend fun createJobs(pipelineRunId: UUID, jobDefinitions: Map<String, JobDefinition>): List<PipelineJob>

    /**
     * Retrieves all jobs for a pipeline run.
     */
    suspend fun findByRun(pipelineRunId: UUID): List<PipelineJob>

    /**
     * Retrieves a job by its unique identifier.
     */
    suspend fun findById(id: UUID): PipelineJob?

    /**
     * Retrieves the currently running job for an agent, if any.
     */
    suspend fun findCurrentByAgent(agentId: UUID): PipelineJob?

    /**
     * Retrieves recent jobs assigned to an agent, ordered by creation
     * time descending.
     */
    suspend fun findByAgent(agentId: UUID, limit: Int = 10): List<PipelineJob>

    /**
     * Claims the next available job matching any of the given labels.
     * Sets the job status to RUNNING and assigns the agent. Returns
     * null if no matching jobs are available.
     */
    suspend fun claimJob(agentId: UUID, labels: List<String>): PipelineJob?

    /**
     * Claims the specific queued [jobId] for [agentId].
     *
     * [previousAgentId] permits an ephemeral child agent to take over a RUNNING job previously
     * reserved by its orchestrator parent. No other RUNNING job can be reassigned.
     */
    suspend fun claimJobById(agentId: UUID, jobId: UUID, previousAgentId: UUID? = null): PipelineJob?

    /**
     * Finds the oldest claimable queued job routed to one of the Kubernetes [profiles].
     *
     * The selected row is locked for the caller's active transaction so concurrent dispatchers
     * can divide work without reserving the same job.
     */
    suspend fun findNextKubernetesDispatchCandidate(profiles: List<String>): PipelineJob?

    /**
     * Associates a queued CI job with its durable Kubernetes dispatch and ephemeral agent.
     *
     * Returns null when the job is no longer queued or another dispatcher already reserved it.
     */
    suspend fun markKubernetesDispatched(
        jobId: UUID,
        dispatchId: UUID,
        agentId: UUID,
    ): PipelineJob?

    /**
     * Finds Kubernetes-dispatched jobs whose terminal lifecycle has not been finalized in CI.
     */
    suspend fun findUnfinalizedKubernetesDispatched(limit: Int): List<PipelineJob>

    /**
     * Claims terminal-result finalization for a Kubernetes-dispatched job.
     *
     * Returns the job only for the transaction that acquired the claim.
     */
    suspend fun claimKubernetesFinalization(jobId: UUID): PipelineJob?

    /**
     * Updates the status of a job. Downstream jobs become claimable
     * automatically when their dependencies reach SUCCESS, picked up by
     * polling runners via [claimJob]. For terminal failure states the
     * caller may supply [errorMessage] explaining why the job failed
     * outside any step (disk check, secret decryption, dead agent, …).
     */
    suspend fun updateStatus(jobId: UUID, status: PipelineRunStatus, errorMessage: String? = null)

    /**
     * Atomically moves an active job to a terminal [status]. A SUCCESS report verifies all declared
     * artifacts first; missing artifacts or verification errors persist FAILURE instead. The last
     * successful producer dispatches artifact completion through the ordinary event path in the transaction.
     *
     * Returns true only for the caller that performed the transition. This is used by external
     * lifecycle reconcilers so multiple server replicas, or an agent completion racing the
     * reconciler, cannot finalize the same job from conflicting outcomes.
     */
    suspend fun finishIfActive(
        jobId: UUID,
        status: PipelineRunStatus,
        errorMessage: String? = null,
    ): Boolean

    /**
     * Resets a FAILED/CANCELLED job (and its steps) back to queued for another attempt (run
     * control). Keeps the requirement-satisfied stamp — satisfied requirements stay satisfied —
     * and refreshes the requirement deadline so a job that failed waiting gets a new window. Returns
     * the reset job, or null when the job was not in a resettable state.
     */
    suspend fun resetForRerun(job: PipelineJob): PipelineJob?

    /**
     * Evaluates the run's queued DEFERRED-condition jobs whose dependencies are all terminal
     * (failure routing): `needs.<dep>.result` and `always()`/`failure()`/`cancelled()`
     * resolve against the actual dependency outcomes. True stamps the job claimable — even over
     * failed dependencies, which is the point — false marks it SKIPPED. Called by the run finalizer
     * whenever a job settles, and after run creation for deferred jobs with no pending dependencies.
     */
    suspend fun evaluateDeferredConditions(pipelineRunId: UUID)

    /** Every queued job whose requirements are declared but not yet verified. */
    suspend fun findAwaitingRequirements(): List<PipelineJob>

    /** [findAwaitingRequirements] scoped to one run — the post-creation check. */
    suspend fun findAwaitingRequirementsByRun(pipelineRunId: UUID): List<PipelineJob>

    /** Stamps the requirement dispatch gate open — idempotent, claim-SQL-visible. */
    suspend fun markRequirementsSatisfied(jobId: UUID)

    /**
     * Explicitly opens a queued job's external artifact/upstream-pipeline requirement gate and
     * records the requesting principal and optional [reason]. Same-run dependencies, conditions,
     * and approvals are unaffected. Throws when the job is not queued, has no external
     * requirements, or the gate has already been opened.
     */
    suspend fun bypassRequirements(jobId: UUID, requestedBy: UUID, reason: String? = null): PipelineJob

    /**
     * Completes the run's GATE jobs — steps-less jobs — whose gates have all cleared:
     * dependencies succeeded, requirements stamped, approval granted where required. Marked SUCCESS
     * server-side, never claimed by an agent. Returns the jobs completed by this pass; callers
     * finalize each (which may cascade further gates).
     */
    suspend fun completeGateJobs(pipelineRunId: UUID): List<PipelineJob>

    /**
     * Approves an approval-gated job. Enforces approve-when-ready: dependencies and
     * requirements must already have cleared, so the approver approves the actual current state.
     * Records approver + comment; the job becomes dispatchable (or completes, for a gate).
     */
    suspend fun approve(jobId: UUID, approvedBy: UUID?, comment: String? = null): PipelineJob

    /** Rejects an approval-gated job — the job FAILS with the rejection recorded. */
    suspend fun rejectApproval(jobId: UUID, rejectedBy: UUID?, comment: String? = null)

    /**
     * True while the job is parked at its approval gate: queued, approval required and ungranted,
     * with every earlier gate (dependencies, requirements) already cleared.
     */
    suspend fun isAwaitingApproval(job: PipelineJob): Boolean

    /**
     * Retrieves a step by its unique identifier.
     */
    suspend fun findStepById(id: UUID): PipelineStep?

    /**
     * Retrieves all steps for a job, ordered by ordinal.
     */
    suspend fun getSteps(jobId: UUID): List<PipelineStep>

    /**
     * Updates the status and exit code of a step. For terminal failure
     * states the agent may supply [errorMessage], a short summary taken
     * from the tail of the step's log output describing why it failed.
     */
    suspend fun updateStepStatus(stepId: UUID, status: PipelineRunStatus, exitCode: Int? = null, errorMessage: String? = null)

    /**
     * Fails any RUNNING jobs whose assigned agent has stopped heartbeating
     * (orphaned) or whose runtime has exceeded their timeout. Returns the
     * jobs that were failed for caller logging.
     */
    suspend fun reapStaleRunningJobs(): List<PipelineJob>

    /**
     * Requests cancellation of the Kubernetes workload associated with [job], if one exists.
     *
     * This is idempotent and is a no-op for jobs dispatched to ordinary polling agents.
     */
    suspend fun cancelKubernetesDispatch(job: PipelineJob)

    /**
     * Cancels all QUEUED jobs in the run that depend on a job which has
     * failed or been cancelled. Cascades transitively: if cancelling job
     * B unblocks a cancel of job C, both are caught in a single pass
     * because the query matches any dep in a terminal-failure state.
     * Returns the jobs that were cancelled.
     */
    suspend fun cancelBlockedJobs(pipelineRunId: UUID): List<PipelineJob>
}

package bosca.git.service

import bosca.git.model.ArtifactDefinition
import bosca.git.model.PipelineDefinition
import bosca.git.model.PipelineExecutionPlan
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineTriggerType
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages pipeline run lifecycle: creation from trigger events, status
 * tracking, cancellation, and re-runs. Handles concurrency group
 * enforcement — cancelling superseded runs when configured.
 */
interface PipelineRunService : Service {

    /**
     * Computes the effective inputs and selected job DAG for a prospective run without persisting it.
     * This applies the same input defaults, conditions, environment selection, approval inheritance,
     * secret declarations, and pruned dependency edges as [createRun]. Promotion history validation is
     * intentionally deferred to run creation because a future phase remains useful in a draft plan.
     */
    fun plan(
        definition: PipelineDefinition,
        ref: String,
        triggerType: PipelineTriggerType,
        parameters: Map<String, String> = emptyMap(),
    ): PipelineExecutionPlan

    /**
     * Creates a new pipeline run. If the pipeline has a concurrency group
     * and cancel-in-progress is enabled, cancels any existing QUEUED or
     * RUNNING runs in the same group before creating the new one.
     *
     * [parameters] are trigger-time inputs (e.g. a release version) injected into every step's
     * environment, so a caller like a release pipeline can run a repo's CI at a specific version
     * without editing its YAML. They are authoritative — they override workflow- and step-level
     * env for their keys — so an injected value can't be shadowed by the definition.
     */
    suspend fun createRun(
        pipelineId: UUID,
        repositoryId: UUID,
        definition: PipelineDefinition,
        commitSha: String,
        ref: String,
        triggerType: PipelineTriggerType,
        triggeredBy: UUID? = null,
        parameters: Map<String, String> = emptyMap()
    ): PipelineRun

    /**
     * Creates an automatic push/tag run once for [triggerId] and [pipelineId]. The occurrence
     * reservation and run/job writes commit together; redelivery returns null without resetting
     * statuses or cancelling other runs. Callers must revalidate [triggeredBy]'s current execution
     * grant before calling, including on redelivery.
     */
    suspend fun createTriggeredRun(
        triggerId: UUID,
        pipelineId: UUID,
        repositoryId: UUID,
        definition: PipelineDefinition,
        commitSha: String,
        ref: String,
        triggerType: PipelineTriggerType,
        triggeredBy: UUID,
    ): PipelineRun?

    /**
     * Retrieves a pipeline run by its unique identifier.
     */
    suspend fun findById(id: UUID): PipelineRun?

    /** The latest run (by number) of [pipelineId] for [ref] — pipeline-requirement evaluation. */
    suspend fun findLatestByPipelineAndRef(pipelineId: UUID, ref: String): PipelineRun?

    /**
     * Lists pipeline runs for a specific pipeline, ordered by number descending.
     */
    suspend fun findByPipeline(pipelineId: UUID, offset: Long = 0, limit: Int = 25): List<PipelineRun>

    /**
     * Lists pipeline runs for a repository, ordered by creation time descending.
     */
    suspend fun findByRepository(repositoryId: UUID, offset: Long = 0, limit: Int = 25): List<PipelineRun>

    /**
     * Lists the release and promotion runs correlated to [releaseId] through their persisted
     * `release.id` trigger parameter. This is the authoritative release-history lookup used by
     * WorkOps; callers do not need to scan repositories or infer a release from a tag name.
     */
    suspend fun findByReleaseId(releaseId: UUID, offset: Long = 0, limit: Int = 25): List<PipelineRun>

    /**
     * The distinct artifacts [runId]'s jobs declared they produce. Each coordinate was
     * resolved against the run context (ref/branch + env + the trigger parameters that carry a release
     * version) when the run was created, so this is a plain read. Empty when the run is gone or declared
     * nothing. This is how a release pipeline discovers "what's available" to use/associate/distribute.
     */
    suspend fun artifacts(runId: UUID): List<ArtifactDefinition>

    /**
     * Permanently deletes a terminal pipeline run and its persisted logs and artifacts. Database
     * relationships cascade the run deletion to its jobs, steps, and artifact records. Active
     * queued or running runs must be cancelled before deletion.
     *
     * @return the deleted run
     */
    suspend fun delete(runId: UUID): PipelineRun

    /**
     * Updates the status of a pipeline run. When all jobs finish, computes
     * the aggregate status and dispatches a [bosca.git.model.PipelineEvent].
     */
    suspend fun updateStatus(runId: UUID, status: PipelineRunStatus)

    /**
     * Cancels a running or queued pipeline run and all its pending jobs.
     * Sends cancel commands to agents executing jobs in this run.
     */
    suspend fun cancelRun(runId: UUID)

    /**
     * Cancels exactly one queued or running pipeline job. The job's downstream blocked jobs and
     * aggregate run state are finalized through the normal terminal-job lifecycle. Throws when
     * the job is already terminal or changes state concurrently.
     */
    suspend fun cancelJob(jobId: UUID): PipelineJob

    /**
     * Creates a new run for the same pipeline and commit as an existing run.
     */
    suspend fun rerun(runId: UUID, triggeredBy: UUID? = null): PipelineRun

    /**
     * Re-runs exactly one FAILED/CANCELLED job of a terminal FAILED/CANCELLED run. The job row keeps
     * its identity, returns to QUEUED with its attempt incremented, and the existing run re-opens.
     * Other terminal jobs remain untouched. [triggeredBy] becomes the run's initiator.
     */
    suspend fun rerunJob(jobId: UUID, triggeredBy: UUID? = null): PipelineJob

    /**
     * Runs [jobId] without waiting for its external artifact or upstream-pipeline requirements.
     * A queued waiting job has only that gate opened. An eligible FAILED/CANCELLED job and run are
     * first reopened for another attempt. Same-run dependencies, deferred conditions, approval
     * gates, and every unrelated dispatch guard remain in force. The exception is attributed to
     * [triggeredBy] with an optional [reason].
     */
    suspend fun runJobAnyway(jobId: UUID, triggeredBy: UUID, reason: String? = null): PipelineJob

    /**
     * Re-runs only the FAILED/CANCELLED jobs of a terminal run (run control) — succeeded
     * jobs keep their results, so a release run with one broken build retries just that build. The
     * run keeps its identity: reset jobs go back to queued (attempt + 1), the run re-opens as
     * QUEUED, and the normal claim/finalize lifecycle completes it again. Satisfied requirements
     * stay satisfied; still-waiting requirement gates get a fresh deadline. [triggeredBy] becomes
     * the run's initiator. Throws when the run is not FAILED/CANCELLED.
     */
    suspend fun rerunFailedJobs(runId: UUID, triggeredBy: UUID? = null): PipelineRun
}

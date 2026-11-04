package bosca.scheduler.service

import bosca.scheduler.model.*
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing cron-based scheduled jobs and their execution history.
 *
 * Scheduled jobs are defined with a cron expression and a reference to a named job definition.
 * The scheduler evaluates due jobs, dispatches them for execution, and tracks each execution
 * as a [JobHistory] entry. Jobs support concurrency control, catch-up execution for missed
 * runs, and can be triggered manually outside of their schedule.
 */
interface SchedulerService : Service {

    /**
     * Retrieves a paginated list of scheduled jobs, optionally filtered by enabled state.
     *
     * @param enabled if non-null, only returns jobs matching this enabled state; `null` returns all jobs
     * @param limit the maximum number of jobs to return
     * @param offset the number of jobs to skip for pagination
     * @return a list of [ScheduledJob] entries matching the criteria
     */
    suspend fun getJobs(enabled: Boolean? = null, limit: Int = 100, offset: Long = 0): List<ScheduledJob>

    /**
     * Retrieves a scheduled job by its unique identifier.
     *
     * @param id the unique identifier of the scheduled job
     * @return the [ScheduledJob], or `null` if not found
     */
    suspend fun getJob(id: UUID): ScheduledJob?

    /** Retrieves scheduled jobs for one registered job definition, using the scheduler's indexed name. */
    suspend fun getJobsByName(jobName: String, limit: Int = 100, offset: Long = 0): List<ScheduledJob>

    /**
     * Retrieves the most recent history entry associated with a specific execution job ID.
     *
     * @param jobId the unique identifier of the execution job (not the scheduled job)
     * @return the [JobHistory] entry, or `null` if no history exists for that job ID
     */
    suspend fun getHistoryByJobId(jobId: UUID): JobHistory?

    /**
     * Creates a new scheduled job from the given input and associates it with the creating principal.
     * The job's next run time is calculated from its cron expression.
     *
     * @param input the job definition including name, cron expression, and parameters
     * @param createdBy the unique identifier of the principal creating this job
     * @return the newly created [ScheduledJob] with its assigned ID and computed next run time
     */
    suspend fun createJob(input: ScheduledJobInput, createdBy: UUID): ScheduledJob

    /**
     * Updates an existing scheduled job's configuration. Recalculates the next run time
     * if the cron expression has changed.
     *
     * @param id the unique identifier of the scheduled job to update
     * @param input the updated job definition
     * @return the updated [ScheduledJob], or `null` if no job exists with the given ID
     */
    suspend fun updateJob(id: UUID, input: ScheduledJobInput): ScheduledJob?

    /**
     * Permanently removes a scheduled job and its associated history entries.
     *
     * @param id the unique identifier of the scheduled job to delete
     */
    suspend fun deleteJob(id: UUID)

    /**
     * Enables a previously disabled scheduled job so it will be picked up by the scheduler
     * on its next evaluation cycle.
     *
     * @param id the unique identifier of the scheduled job to enable
     * @return the updated [ScheduledJob] with `enabled = true`, or `null` if not found
     */
    suspend fun enableJob(id: UUID): ScheduledJob?

    /**
     * Disables a scheduled job, preventing the scheduler from executing it automatically.
     * The job can still be triggered manually via [triggerJob].
     *
     * @param id the unique identifier of the scheduled job to disable
     * @return the updated [ScheduledJob] with `enabled = false`, or `null` if not found
     */
    suspend fun disableJob(id: UUID): ScheduledJob?

    /**
     * Assigns the principal whose permissions a principal-required job will execute with.
     * A null [confirmedBy] leaves the job pending consent and disabled; otherwise it becomes active.
     */
    suspend fun assignExecutionPrincipal(
        id: UUID,
        principalId: UUID,
        assignedBy: UUID,
        confirmedBy: UUID? = null,
    ): ScheduledJob?

    /** Confirms the current pending execution-principal assignment and enables the job. */
    suspend fun confirmExecutionPrincipal(id: UUID, confirmedBy: UUID): ScheduledJob?

    /** Clears an execution-principal assignment and parks the job visibly. */
    suspend fun clearExecutionPrincipal(id: UUID): ScheduledJob?

    /** Parks a job whose assigned principal became missing, disabled, or unauthorized. */
    suspend fun parkNeedsPrincipal(id: UUID): ScheduledJob?

    /**
     * Manually triggers an immediate execution of the specified scheduled job, regardless
     * of its cron schedule or enabled state. Creates a history entry with the trigger recorded.
     *
     * @param id the unique identifier of the scheduled job to trigger
     * @return the [JobHistory] entry for this triggered execution, or `null` if the job was not found
     */
    suspend fun triggerJob(id: UUID): JobHistory?

    /**
     * Cancels a pending or running job execution by marking its status as CANCELLED.
     * Only jobs with PENDING or RUNNING status can be cancelled; attempting to cancel
     * a job in any other state will fail.
     *
     * @param historyId the unique identifier of the job history entry to cancel
     * @return the updated [JobHistory] entry with CANCELLED status
     */
    suspend fun cancelJob(historyId: UUID): JobHistory

    /**
     * Retrieves a paginated list of execution history entries for a specific scheduled job,
     * with optional filtering by execution status and trigger source.
     *
     * @param scheduledJobId the unique identifier of the scheduled job
     * @param limit the maximum number of history entries to return
     * @param offset the number of entries to skip for pagination
     * @param status an optional filter to only include entries with this execution status
     * @param source an optional filter to only include entries triggered by this source (SCHEDULER or EVENT)
     * @return a list of [JobHistory] entries matching the criteria
     */
    suspend fun getHistory(
        scheduledJobId: UUID,
        limit: Int = 100,
        offset: Long = 0,
        status: ScheduleExecutionStatus? = null,
        source: JobHistorySource? = null
    ): List<JobHistory>

    /**
     * Returns the total number of history entries for a specific scheduled job,
     * with optional filtering by status and source. Useful for pagination alongside [getHistory].
     *
     * @param scheduledJobId the unique identifier of the scheduled job
     * @param status an optional filter to only count entries with this execution status
     * @param source an optional filter to only count entries triggered by this source
     * @return the total count of matching history entries
     */
    suspend fun countHistory(
        scheduledJobId: UUID,
        status: ScheduleExecutionStatus? = null,
        source: JobHistorySource? = null
    ): Long

    /**
     * Retrieves a paginated list of execution history entries across all scheduled jobs,
     * with optional filtering by status and source.
     *
     * @param limit the maximum number of history entries to return
     * @param offset the number of entries to skip for pagination
     * @param status an optional filter to only include entries with this execution status
     * @param source an optional filter to only include entries triggered by this source
     * @return a list of [JobHistory] entries matching the criteria
     */
    suspend fun getAllHistory(
        limit: Int = 100,
        offset: Long = 0,
        status: ScheduleExecutionStatus? = null,
        source: JobHistorySource? = null
    ): List<JobHistory>

    /**
     * Returns the total number of history entries across all scheduled jobs,
     * with optional filtering by status and source. Useful for pagination alongside [getAllHistory].
     *
     * @param status an optional filter to only count entries with this execution status
     * @param source an optional filter to only count entries triggered by this source
     * @return the total count of matching history entries
     */
    suspend fun countAllHistory(
        status: ScheduleExecutionStatus? = null,
        source: JobHistorySource? = null
    ): Long

    /**
     * Returns metadata about all registered job definitions that can be used when
     * creating scheduled jobs, including their names, queue assignments, and parameter schemas.
     *
     * @return a list of [JobDefinitionInfo] describing each available job type
     */
    suspend fun getAvailableJobDefinitions(): List<JobDefinitionInfo>

    /**
     * Parses and validates a cron expression, returning whether it is syntactically correct
     * and, if valid, a preview of upcoming execution times.
     *
     * @param expression the cron expression string to validate
     * @return a [CronValidationResult] indicating validity, any parse error, and next scheduled runs
     */
    suspend fun validateCronExpression(expression: String): CronValidationResult

    /**
     * Updates the execution status of a job history entry, optionally recording an error
     * message and execution context. Used by job executors to report progress and outcomes.
     *
     * @param jobId the unique identifier of the execution job whose status should be updated
     * @param status the new execution status (e.g., RUNNING, COMPLETED, FAILED)
     * @param errorMessage an optional error message if the job failed
     * @param context optional JSON context data to associate with this execution
     * @return the updated [JobHistory] entry, or `null` if no history exists for the given job ID
     */
    suspend fun updateJobStatus(jobId: UUID, status: ScheduleExecutionStatus, errorMessage: String? = null, context: JsonElement? = null): JobHistory?

    /**
     * Updates the execution status of a job history entry matched by its history primary key,
     * rather than the queue correlation `job_id`. Use this for terminal-state records that
     * were never enqueued — for example, `SKIPPED` rows recorded when concurrent-execution
     * gating blocks a scheduled run (no queue job exists, so `job_id` is `UUID.NIL`).
     *
     * @param historyId the primary key of the [JobHistory] row to update
     * @param status the new execution status
     * @param errorMessage an optional explanation for the status change
     * @return the updated [JobHistory] entry, or `null` if no row exists with the given id
     */
    suspend fun updateJobStatusByHistoryId(historyId: UUID, status: ScheduleExecutionStatus, errorMessage: String? = null): JobHistory?

    /**
     * Associates a history entry with a specific execution job ID after the job has been
     * dispatched to the job queue. This links the scheduler's history tracking with the
     * underlying job system's execution.
     *
     * @param historyId the unique identifier of the job history entry
     * @param jobId the unique identifier of the dispatched execution job
     */
    suspend fun setJobId(historyId: UUID, jobId: UUID)

    /**
     * Retrieves all enabled scheduled jobs whose next run time is at or before the current time,
     * indicating they should be dispatched for execution.
     *
     * @return a list of [ScheduledJob] entries that are due for execution
     */
    suspend fun getDueJobs(): List<ScheduledJob>

    /**
     * Records the completion of a job run by updating the last run timestamp and setting
     * the next scheduled run time based on the cron expression.
     *
     * @param id the unique identifier of the scheduled job
     * @param lastRunAt the timestamp when the job was last executed
     * @param nextRunAt the computed timestamp for the next execution, or `null` if the cron expression
     *   does not yield a future run
     * @return the updated [ScheduledJob], or `null` if not found
     */
    suspend fun updateJobRunTimes(id: UUID, lastRunAt: OffsetDateTime, nextRunAt: OffsetDateTime?): ScheduledJob?

    /**
     * Checks whether the specified scheduled job has any recent executions currently in PENDING
     * or RUNNING status. Records older than two hours are considered stale and excluded,
     * preventing orphaned history entries from permanently blocking scheduled jobs.
     *
     * @param scheduledJobId the unique identifier of the scheduled job
     * @return `true` if there are recent active (pending or running) executions, `false` otherwise
     */
    suspend fun hasActiveExecutions(scheduledJobId: UUID): Boolean

    /**
     * Marks job history records stuck in PENDING or RUNNING status beyond the expected
     * execution window as STALE. This prevents orphaned records from permanently blocking
     * scheduled jobs that disallow concurrent execution, while distinguishing them from
     * jobs that actively errored.
     *
     * @return the number of stale records that were marked
     */
    suspend fun cleanupStaleExecutions(): Long

    /**
     * Retention: delete finished job-history rows whose `completed_at` is older than [before], so the
     * `/system/jobs` history (and the `scheduler.job_history` table) stays bounded. In-flight
     * (pending/running) rows are never removed. The scheduled job-history maintenance job calls
     * this periodically.
     *
     * @return the number of history rows deleted
     */
    suspend fun purgeHistoryBefore(before: OffsetDateTime): Long

    /**
     * Creates a new job history entry to record an execution attempt. This is called when
     * a job is dispatched, either by the scheduler or via a manual trigger.
     *
     * @param scheduledJobId the scheduled job this execution belongs to, or `null` for ad-hoc jobs
     * @param jobId the unique identifier of the dispatched execution job
     * @param name a human-readable name for this execution record
     * @param scheduledFor the time this execution was originally scheduled for
     * @param source whether this execution was triggered by the SCHEDULER or an EVENT (manual trigger)
     * @param wasCatchUp whether this execution is a catch-up run for a missed schedule
     * @param delayedUntil if the execution was delayed, the time it was deferred until
     * @param definition optional JSON snapshot of the job definition at the time of dispatch
     * @param context optional JSON context data to associate with this execution
     * @return the newly created [JobHistory] entry
     */
    suspend fun createHistory(
        scheduledJobId: UUID? = null,
        jobId: UUID,
        name: String,
        scheduledFor: OffsetDateTime,
        source: JobHistorySource = JobHistorySource.EVENT,
        wasCatchUp: Boolean = false,
        delayedUntil: OffsetDateTime? = null,
        definition: JsonElement? = null,
        context: JsonElement? = null,
        parentJobId: UUID? = null
    ): JobHistory

    /**
     * Records that an existing in-flight execution was re-enqueued — typically because the
     * executor threw `DelayException` and the runner put the job back on the queue.
     *
     * Updates the currently-active row (`status IN ('pending', 'running')`) for [jobId] in
     * place: bumps `triggered_at` to now, overwrites `scheduled_for`/`delayed_until` with the
     * new intended fire time, and resets the row to `pending`. Returns the updated row when
     * an active one existed, or `null` when no active row is present (the caller should then
     * insert a fresh history entry via [createHistory] to record the new attempt).
     *
     * Collapsing re-enqueues onto a single row per `job_id` prevents the admin UI from
     * displaying a growing ladder of duplicate "pending" rows whenever a job bounces back
     * through the queue.
     */
    suspend fun refreshPendingHistory(
        jobId: UUID,
        scheduledFor: OffsetDateTime,
        delayedUntil: OffsetDateTime?
    ): JobHistory?
}

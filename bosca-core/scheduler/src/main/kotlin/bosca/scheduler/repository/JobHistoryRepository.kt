package bosca.scheduler.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.scheduler.model.JobHistory
import bosca.scheduler.model.JobHistorySource
import bosca.scheduler.model.ScheduleExecutionStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface JobHistoryRepository {

    @Query("""
        INSERT INTO scheduler.job_history (
            scheduled_job_id, job_id, name, scheduled_for, triggered_at, source, status, was_catch_up, delayed_until, parent_job_id, definition, context
        ) VALUES (
            :scheduledJobId, :jobId, :name, :scheduledFor, :triggeredAt, :source::scheduler.job_history_source, :status::scheduler.execution_status, :wasCatchUp, :delayedUntil, :parentJobId, :definition::jsonb, :context::jsonb
        ) RETURNING *
    """)
    suspend fun add(entry: JobHistory): JobHistory

    @Query("SELECT * FROM scheduler.job_history WHERE id = :id")
    suspend fun getById(id: UUID): JobHistory?

    @Query("SELECT * FROM scheduler.job_history WHERE job_id = :jobId")
    suspend fun getByJobId(jobId: UUID): JobHistory?

    @Query("""
        SELECT * FROM scheduler.job_history
        WHERE scheduled_job_id = :scheduledJobId
          AND (:status IS NULL OR status = :status::scheduler.execution_status)
          AND (:source IS NULL OR source = :source::scheduler.job_history_source)
        ORDER BY triggered_at DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getByScheduledJobId(
        scheduledJobId: UUID,
        limit: Int,
        offset: Long,
        status: ScheduleExecutionStatus? = null,
        source: JobHistorySource? = null
    ): List<JobHistory>

    @Query("""
        SELECT COUNT(*) FROM scheduler.job_history
        WHERE scheduled_job_id = :scheduledJobId
          AND (:status IS NULL OR status = :status::scheduler.execution_status)
          AND (:source IS NULL OR source = :source::scheduler.job_history_source)
    """)
    suspend fun countByScheduledJobId(
        scheduledJobId: UUID,
        status: ScheduleExecutionStatus? = null,
        source: JobHistorySource? = null
    ): Long

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM scheduler.job_history
            WHERE scheduled_job_id = :scheduledJobId
              AND status IN ('pending', 'running')
              AND triggered_at > NOW() - INTERVAL '2 hours'
        )
    """)
    suspend fun hasActiveExecutions(scheduledJobId: UUID): Boolean

    /**
     * Applies a status update to the tracking row for [jobId].
     *
     * Excludes rows already in `cancelled` state so that a late RUNNING/COMPLETE event
     * emitted by a worker that didn't yet notice the cancellation cannot undo the
     * administrator's decision. Terminal `failed` / `completed` rows are intentionally
     * *not* excluded: the runner retries failed jobs under the same `job_id`, and the
     * resulting RUNNING/COMPLETE events must still win — otherwise a retry that
     * eventually succeeds would be displayed forever as "failed".
     */
    @Query("""
        UPDATE scheduler.job_history SET
            status = :status::scheduler.execution_status,
            completed_at = :completedAt,
            error_message = :errorMessage,
            context = :context::jsonb
        WHERE job_id = :jobId AND status <> 'cancelled'::scheduler.execution_status
        RETURNING *
    """)
    suspend fun updateStatus(
        jobId: UUID,
        status: ScheduleExecutionStatus,
        completedAt: OffsetDateTime?,
        errorMessage: String?,
        context: JsonElement?
    ): JobHistory?

    /**
     * Updates the in-flight row for [jobId] to reflect a re-enqueue, without inserting a
     * new row.
     *
     * Event-triggered jobs are re-queued by the runner on [DelayException][bosca.sharedqueue.jobs.DelayException]
     * (executor-lock contention, scheduled-valid windows that haven't arrived yet, etc.). Each
     * re-queue used to emit a fresh enqueue event that the forwarder insert-logged, producing
     * a ladder of "pending" rows with the same `job_id`. This query collapses that ladder: if
     * an active row already exists we refresh its `scheduled_for`/`delayed_until` in place and
     * bounce its status back to `pending`, returning the row. If no active row exists (terminal
     * state reached, or this is the first-ever event) the caller must fall back to [add] so the
     * new attempt is recorded.
     *
     * `triggered_at` is deliberately *not* refreshed: it must keep pointing at the original
     * dispatch time so [cleanupStaleExecutions]/[countStaleRecords] can age out jobs that
     * bounce indefinitely (e.g. an executor stuck in a `DelayException` loop). Rewriting
     * `triggered_at = NOW()` on every re-enqueue would make such rows appear perpetually
     * fresh and they would never be marked stale.
     */
    @Query("""
        UPDATE scheduler.job_history SET
            scheduled_for = :scheduledFor,
            delayed_until = :delayedUntil,
            status = 'pending'::scheduler.execution_status,
            completed_at = NULL,
            error_message = NULL
        WHERE job_id = :jobId AND status IN ('pending', 'running')
        RETURNING *
    """)
    suspend fun refreshPendingRow(
        jobId: UUID,
        scheduledFor: OffsetDateTime,
        delayedUntil: OffsetDateTime?
    ): JobHistory?

    /**
     * Updates the status of a job history entry by its primary key, unlike [updateStatus]
     * which matches on the queue correlation ID (`job_id`). Use this when cancelling a job
     * by its history entry ID, where the queue correlation ID may not yet be assigned.
     */
    @Query("""
        UPDATE scheduler.job_history SET
            status = :status::scheduler.execution_status,
            completed_at = :completedAt,
            error_message = :errorMessage
        WHERE id = :id RETURNING *
    """)
    suspend fun updateStatusById(
        id: UUID,
        status: ScheduleExecutionStatus,
        completedAt: OffsetDateTime?,
        errorMessage: String?
    ): JobHistory?

    /**
     * Atomically cancels a job history entry by setting its status to the given [status],
     * but only if the current status is `pending` or `running`. Returns the updated row,
     * or `null` if the row does not exist or has already transitioned to a terminal state.
     *
     * This avoids TOCTOU races where a separate SELECT-then-UPDATE could allow the job
     * to transition between the check and the update.
     */
    @Query("""
        UPDATE scheduler.job_history SET
            status = :status::scheduler.execution_status,
            completed_at = :completedAt,
            error_message = :errorMessage
        WHERE id = :id AND status IN ('pending', 'running')
        RETURNING *
    """)
    suspend fun cancelById(
        id: UUID,
        status: ScheduleExecutionStatus,
        completedAt: OffsetDateTime?,
        errorMessage: String?
    ): JobHistory?

    @Query("UPDATE scheduler.job_history SET job_id = :jobId WHERE id = :entryId")
    suspend fun setJobId(entryId: UUID, jobId: UUID)

    @Query("""
        SELECT * FROM scheduler.job_history
        WHERE (:status IS NULL OR status = :status::scheduler.execution_status)
          AND (:source IS NULL OR source = :source::scheduler.job_history_source)
        ORDER BY triggered_at DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getAll(
        limit: Int,
        offset: Long,
        status: ScheduleExecutionStatus? = null,
        source: JobHistorySource? = null
    ): List<JobHistory>

    @Query("""
        SELECT COUNT(*) FROM scheduler.job_history
        WHERE (:status IS NULL OR status = :status::scheduler.execution_status)
          AND (:source IS NULL OR source = :source::scheduler.job_history_source)
    """)
    suspend fun countAll(
        status: ScheduleExecutionStatus? = null,
        source: JobHistorySource? = null
    ): Long

    @Query("""
        SELECT COUNT(*) FROM scheduler.job_history
        WHERE status IN ('pending', 'running')
          AND triggered_at < :cutoff
    """)
    suspend fun countStaleRecords(cutoff: OffsetDateTime): Long

    @Query("""
        UPDATE scheduler.job_history
        SET status = CAST('stale' AS scheduler.execution_status),
            completed_at = NOW(),
            error_message = :errorMessage
        WHERE status IN ('pending', 'running')
          AND triggered_at < :cutoff
    """)
    suspend fun markStaleRecords(cutoff: OffsetDateTime, errorMessage: String)

    @Query("DELETE FROM scheduler.job_history WHERE scheduled_job_id = :scheduledJobId")
    suspend fun deleteByScheduledJobId(scheduledJobId: UUID)

    /**
     * Delete finished history rows older than [cutoff] (retention). Only terminal rows are removed —
     * a finished row has `completed_at` set, so an in-flight (pending/running) row, whose `completed_at`
     * is NULL, is always kept. Returns how many rows were deleted.
     */
    @Query(
        value = "DELETE FROM scheduler.job_history WHERE completed_at IS NOT NULL AND completed_at < :cutoff",
        returnUpdateCount = true,
    )
    suspend fun deleteCompletedBefore(cutoff: OffsetDateTime): Int
}

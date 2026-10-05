package bosca.scheduler.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Tracks each execution of a job.
 */
@Serializable
data class JobHistory(
    val id: UUID,
    @ColumnName("scheduled_job_id")
    val scheduledJobId: UUID? = null,
    @ColumnName("job_id")
    val jobId: UUID,
    val name: String? = null,
    @ColumnName("scheduled_for")
    val scheduledFor: OffsetDateTime,
    @ColumnName("triggered_at")
    val triggeredAt: OffsetDateTime,
    val source: JobHistorySource = JobHistorySource.EVENT,
    val status: ScheduleExecutionStatus = ScheduleExecutionStatus.PENDING,
    @ColumnName("completed_at")
    val completedAt: OffsetDateTime? = null,
    @ColumnName("error_message")
    val errorMessage: String? = null,
    @ColumnName("was_catch_up")
    val wasCatchUp: Boolean = false,
    @ColumnName("delayed_until")
    val delayedUntil: OffsetDateTime? = null,
    /**
     * The `job_id` of the enclosing parent execution, if this row represents a child
     * fanned out from a multi-job (or other composite executor). `null` for top-level
     * jobs. Populated at insert time via [JobEnqueueEvent.parentJobId] so the admin UI
     * can group children under the parent that spawned them.
     */
    @ColumnName("parent_job_id")
    val parentJobId: UUID? = null,
    val definition: JsonElement? = null,
    val context: JsonElement? = null
)

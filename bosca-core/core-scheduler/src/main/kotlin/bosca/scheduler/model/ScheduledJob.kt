package bosca.scheduler.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Defines a recurring or one-time scheduled job.
 */
@Serializable
data class ScheduledJob(
    val id: UUID,
    val name: String,
    val description: String? = null,
    @ColumnName("job_name")
    val jobName: String,
    @ColumnName("job_parameters")
    val jobParameters: JsonElement = JsonObject(emptyMap()),
    @ColumnName("cron_expression")
    val cronExpression: String,
    val enabled: Boolean = true,
    @ColumnName("allow_concurrent")
    val allowConcurrent: Boolean = false,
    @ColumnName("catch_up")
    val catchUp: Boolean = false,
    @ColumnName("max_catch_up")
    val maxCatchUp: Int = 1,
    @ColumnName("created_at")
    val createdAt: OffsetDateTime,
    @ColumnName("updated_at")
    val updatedAt: OffsetDateTime,
    @ColumnName("created_by")
    val createdBy: UUID,
    /** Principal whose permissions this job executes with; distinct from the record's creator. */
    @ColumnName("execution_principal_id")
    val executionPrincipalId: UUID? = null,
    @ColumnName("principal_state")
    val principalState: ScheduledJobPrincipalState = ScheduledJobPrincipalState.NOT_REQUIRED,
    @ColumnName("principal_assigned_by")
    val principalAssignedBy: UUID? = null,
    @ColumnName("principal_confirmed_by")
    val principalConfirmedBy: UUID? = null,
    @ColumnName("last_run_at")
    val lastRunAt: OffsetDateTime? = null,
    @ColumnName("next_run_at")
    val nextRunAt: OffsetDateTime? = null
)

/** Lifecycle for a scheduled job's server-owned execution-principal assignment. */
@DbMapper(ScheduledJobPrincipalStateMapper::class)
@Serializable
enum class ScheduledJobPrincipalState {
    /** Infrastructure job that intentionally runs without an end-user execution identity. */
    NOT_REQUIRED,

    /** Principal-required job with no currently eligible confirmed assignment. */
    NEEDS_PRINCIPAL,

    /** A different person was assigned and must consent before execution. */
    PENDING_CONFIRMATION,

    /** The assigned principal self-assigned, consented, or was confirmed by an administrator. */
    ACTIVE,
}

object ScheduledJobPrincipalStateMapper :
    EnumMapper<ScheduledJobPrincipalState>({ ScheduledJobPrincipalState.valueOf(it.uppercase()) })

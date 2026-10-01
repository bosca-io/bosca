package bosca.workops.model.bulk

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * R24 — every kind of bulk-edit Work Ops supports. Variants land
 * iteratively — `EditField`, `Transition`, `Delete`, `Restore`,
 * `AssignTo`, `AddLabel`, `RemoveLabel`. Phase 10 ships the
 * persistence shape; the executor binds variants as their
 * underlying services land.
 */
@Serializable
sealed class BulkOperation {
    @Serializable @SerialName("EditField")
    data class EditField(val fieldKey: String, val newValue: String) : BulkOperation()

    @Serializable @SerialName("Transition")
    data class Transition(@Contextual val transitionId: UUID) : BulkOperation()

    @Serializable @SerialName("AssignTo")
    data class AssignTo(@Contextual val profileId: UUID) : BulkOperation()

    @Serializable @SerialName("AddLabel")
    data class AddLabel(@Contextual val labelId: UUID) : BulkOperation()

    @Serializable @SerialName("RemoveLabel")
    data class RemoveLabel(@Contextual val labelId: UUID) : BulkOperation()

    @Serializable @SerialName("Delete")
    data object Delete : BulkOperation()
}

/**
 * Terminal state of a completed bulk operation.
 */
@Serializable
enum class BulkOperationState {
    DRY_RUN,
    COMPLETED,
    PARTIAL,
}

/**
 * Job handle returned by `bulkUpdateTasks`. Phase 10's GraphQL
 * subscription streams progress events keyed by the handle id.
 *
 * [errorDetails] accumulates a human-readable description for every
 * task that failed during execution (format: "task {key}: {message}").
 * Callers can inspect this list to understand which tasks failed and why
 * rather than relying solely on the aggregate [errors] count.
 */
@Serializable
data class BulkOperationHandle(
    @Contextual val id: UUID,
    val total: Long,
    val processed: Long,
    val errors: Long,
    val errorDetails: List<String>,
    val state: BulkOperationState,
    @Contextual val startedAt: OffsetDateTime,
    @Contextual val completedAt: OffsetDateTime?,
    val dryRun: Boolean,
)

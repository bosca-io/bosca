package bosca.workops.model.workflow

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Coarse classification that drives reporting roll-ups (R4). Burn-down
 * counts only `DONE` as resolved; sprint scope-creep math counts only
 * `DONE` and `CANCELLED` as closed. Per-status display ordering and
 * color come from the individual [Status]; the category is the
 * computational handle that downstream reports key off.
 */
@Serializable
enum class StatusCategory {
    /** Not yet started. */
    TODO,

    /** Actively being worked. */
    IN_PROGRESS,

    /** Reached an end state successfully. */
    DONE,

    /** Reached an end state without being completed (won't fix, duplicate, etc.). */
    CANCELLED,
}

/**
 * One state a task can sit in (R4). A status is workflow-engine input:
 * Phase 3's `Workflow` references statuses through `WorkflowState`
 * and edges them together with `WorkflowTransition`s. Statuses have no
 * project scope of their own — they are global definitions composed
 * into per-project workflows — so the same `In Review` status can be
 * shared across every team's workflow without duplication.
 *
 * @property category drives reporting roll-ups (see [StatusCategory]).
 * @property colorHex 7-character `#RRGGBB`.
 */
@BatchKey("id")
@Serializable
data class Status(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    val category: StatusCategory,
    @ColumnName("color_hex")
    val colorHex: String,
    val version: Long = 0,
)

/** Input for creating a [Status] (maps to `CreateWorkOpsStatusInput` in GraphQL). */
@Serializable
data class CreateStatusInput(
    val name: String,
    val description: String? = null,
    val category: StatusCategory,
    val colorHex: String,
)

/** Input for updating a [Status] (maps to `UpdateWorkOpsStatusInput` in GraphQL). */
@Serializable
data class UpdateStatusInput(
    val name: String,
    val description: String? = null,
    val category: StatusCategory,
    val colorHex: String,
    val expectedVersion: Long,
)

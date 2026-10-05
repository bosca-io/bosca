package bosca.workops.model.task

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * The urgency / importance label attached to a task (R2's `priorityId`
 * field). Phase 2 seeds the canonical Lowest / Low / Medium / High /
 * Highest / Critical scale; admins may add organization-specific
 * priorities as needed.
 *
 * Priority is a free row rather than an enum because customers regularly
 * extend the scale (e.g. adding `Sev0` above `Critical`) and because
 * each priority needs an icon and color that ships with the row, not
 * with the schema migration.
 *
 * @property displayOrder controls dropdown ordering. Lower values sort
 *                        first; the Phase 2 seeds run from 0 (Lowest)
 *                        to 5 (Critical) so the UI's natural rendering
 *                        matches escalation severity.
 */
@BatchKey("id")
@Serializable
data class Priority(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    @ColumnName("icon_key")
    val iconKey: String,
    @ColumnName("color_hex")
    val colorHex: String,
    @ColumnName("display_order")
    val displayOrder: Int,
    val version: Long = 0,
)

/** Input for creating a [Priority] (maps to `CreateWorkOpsPriorityInput` in GraphQL). */
@Serializable
data class CreatePriorityInput(
    val name: String,
    val description: String? = null,
    val iconKey: String,
    val colorHex: String,
    val displayOrder: Int,
)

/** Input for updating a [Priority] (maps to `UpdateWorkOpsPriorityInput` in GraphQL). */
@Serializable
data class UpdatePriorityInput(
    val name: String,
    val description: String? = null,
    val iconKey: String,
    val colorHex: String,
    val displayOrder: Int,
    val expectedVersion: Long,
)

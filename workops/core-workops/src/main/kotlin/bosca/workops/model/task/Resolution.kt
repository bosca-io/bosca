package bosca.workops.model.task

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * The "how was this task closed" tag set on resolution (R9). Resolutions
 * are global like statuses — Phase 2 seeds the canonical set (`Done`,
 * `Won't Fix`, `Duplicate`, `Cannot Reproduce`, `Incomplete`, `Fixed`,
 * `Declined`) and admins may add more.
 *
 * The split between [Status] and [Resolution] is deliberate: a status is
 * "where the task is" (workflow position), a resolution is "why it
 * stopped here" (closure reason). A task in the `Closed` status with
 * `Won't Fix` resolution differs in reporting from `Closed` with
 * `Done` even though both look the same on a board.
 *
 * @property displayOrder dropdown ordering, lower first.
 */
@BatchKey("id")
@Serializable
data class Resolution(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    @ColumnName("display_order")
    val displayOrder: Int,
    val version: Long = 0,
)

/** Input for creating a [Resolution] (maps to `CreateWorkOpsResolutionInput` in GraphQL). */
@Serializable
data class CreateResolutionInput(
    val name: String,
    val description: String? = null,
    val displayOrder: Int,
)

/** Input for updating a [Resolution] (maps to `UpdateWorkOpsResolutionInput` in GraphQL). */
@Serializable
data class UpdateResolutionInput(
    val name: String,
    val description: String? = null,
    val displayOrder: Int,
    val expectedVersion: Long,
)

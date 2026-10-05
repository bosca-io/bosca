package bosca.workops.model.links

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Coarse classification driving link-graph rules (R7).
 *
 * Cycle detection runs on `BLOCKS`-category links bounded by depth
 * 256; deeper graphs reject as `GRAPH_TOO_DEEP` rather than paying
 * for an unbounded BFS. Other categories permit cycles — a
 * `RELATES_TO` ring among a cluster of tasks is legitimate planning
 * shorthand.
 */
@DbMapper(LinkCategoryMapper::class)
@Serializable
enum class LinkCategory {
    BLOCKS,
    DUPLICATES,
    RELATES_TO,
    CLONES,
    CAUSES,
    CUSTOM,
}

object LinkCategoryMapper : EnumMapper<LinkCategory>({ LinkCategory.valueOf(it.uppercase()) })

/**
 * Defines a kind of link an admin can attach between two tasks (R7).
 * The system seeds Blocks / Duplicates / Relates / Clones / Causes;
 * admins may add organization-specific link types under
 * [LinkCategory.CUSTOM] (which the engine treats as "no cycle
 * checks").
 *
 * @property inwardLabel rendered on the *target* task's UI ("is
 *                       blocked by"), so the link reads naturally
 *                       in both directions without storing the same
 *                       relationship twice.
 * @property outwardLabel rendered on the *source* task's UI
 *                        ("blocks").
 */
@BatchKey("id")
@Serializable
data class TaskLinkType(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    @ColumnName("inward_label")
    val inwardLabel: String,
    @ColumnName("outward_label")
    val outwardLabel: String,
    val category: LinkCategory,
    val version: Long = 0,
)

/**
 * One directed link from `sourceTaskId` → `targetTaskId` of type
 * [linkTypeId] (R7). Self-links are rejected by the service. The
 * unique constraint `(source_task_id, target_task_id, link_type_id)`
 * enforces idempotent linking — calling `linkTasks(A, B, BLOCKS)`
 * twice creates one row.
 */
@BatchKey("id")
@Serializable
data class TaskLink(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("link_type_id")
    @Contextual
    val linkTypeId: UUID,
    @ColumnName("source_task_id")
    @Contextual
    val sourceTaskId: UUID,
    @ColumnName("target_task_id")
    @Contextual
    val targetTaskId: UUID,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("created_by_principal_id")
    @Contextual
    val createdByPrincipalId: UUID,
)

/** Input for creating a [TaskLink]. */
@Serializable
data class TaskLinkInput(
    @Contextual
    val linkTypeId: UUID,
    @Contextual
    val sourceTaskId: UUID,
    @Contextual
    val targetTaskId: UUID,
)

/** Input for creating a [TaskLinkType] (maps to `CreateWorkOpsTaskLinkTypeInput` in GraphQL). */
@Serializable
data class CreateTaskLinkTypeInput(
    val name: String,
    val inwardLabel: String,
    val outwardLabel: String,
    val category: LinkCategory,
)

/** Input for updating a [TaskLinkType] (maps to `UpdateWorkOpsTaskLinkTypeInput` in GraphQL). */
@Serializable
data class UpdateTaskLinkTypeInput(
    val name: String,
    val inwardLabel: String,
    val outwardLabel: String,
    val category: LinkCategory,
    val expectedVersion: Long,
)

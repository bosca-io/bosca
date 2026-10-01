package bosca.workops.model.task

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Where in the parent / child hierarchy a [TaskType] sits (R3). The
 * numeric ordering is significant: parents must sit at a strictly
 * higher level than children, and the Phase 3 link engine (R7)
 * enforces that subtasks parent only `STANDARD` tasks, epics parent
 * only `STANDARD` tasks, and initiatives parent only `EPIC` tasks.
 *
 * Storing a numeric `level` rather than separate boolean flags lets a
 * single integer comparison validate any parent / child pair.
 */
@Serializable
enum class TaskHierarchyLevel(val level: Int) {
    /** A child of a STANDARD task. May not itself have sub-tasks (single-level only). */
    SUBTASK(-1),

    /** The default level — what users mean when they say "task". */
    STANDARD(0),

    /** Groups STANDARD tasks under a single deliverable bar. */
    EPIC(1),

    /** Cross-program rollup that groups epics. R3 caps the chain at INITIATIVE → EPIC → STANDARD → SUBTASK. */
    INITIATIVE(2),
}

/**
 * Configurable type label for a task — Bug, Story, Task, Epic,
 * Sub-task, plus organization-specific extensions like "Editorial
 * Brief" or "Asset Refresh" (R3). [hierarchyLevel] controls which
 * other types can parent or be parented by tasks of this type.
 *
 * @property iconKey resolved by the admin UI's icon catalog rather
 *                   than emitting raw paths. Catalog membership is
 *                   validated at write time.
 * @property colorHex 7-character `#RRGGBB`. Validation lives in the
 *                    service layer, not the GraphQL scalar, so seed
 *                    rows can be loaded by a migration without round-
 *                    tripping through GraphQL.
 */
@BatchKey("id")
@Serializable
data class TaskType(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    @ColumnName("icon_key")
    val iconKey: String,
    @ColumnName("color_hex")
    val colorHex: String,
    @ColumnName("hierarchy_level")
    val hierarchyLevel: TaskHierarchyLevel = TaskHierarchyLevel.STANDARD,
    val version: Long = 0,
)

/** Input for creating a [TaskType] (maps to `CreateWorkOpsTaskTypeInput` in GraphQL). */
@Serializable
data class CreateTaskTypeInput(
    val name: String,
    val description: String? = null,
    val iconKey: String,
    val colorHex: String,
    val hierarchyLevel: TaskHierarchyLevel = TaskHierarchyLevel.STANDARD,
)

/** Input for updating a [TaskType] (maps to `UpdateWorkOpsTaskTypeInput` in GraphQL). */
@Serializable
data class UpdateTaskTypeInput(
    val name: String,
    val description: String? = null,
    val iconKey: String,
    val colorHex: String,
    val hierarchyLevel: TaskHierarchyLevel = TaskHierarchyLevel.STANDARD,
    val expectedVersion: Long,
)

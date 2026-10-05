package bosca.workops.model.sprint

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@DbMapper(SprintStateMapper::class)
@Serializable
enum class SprintState {
    /** Created and being planned, not yet started. */
    FUTURE,

    /** The board's running sprint. */
    ACTIVE,

    /** Closed; all unfinished tasks have been moved per the close mapping. */
    CLOSED,
}

object SprintStateMapper : EnumMapper<SprintState>({ SprintState.valueOf(it.uppercase()) })

/**
 * One iteration of work bound to a [Board] (R8). The
 * `committedTaskIds` snapshot is captured at start; tasks added later
 * land in `addedDuringSprintTaskIds` so scope-creep reporting is
 * meaningful. Closing a sprint with unfinished tasks requires a
 * destination per task — no work is silently orphaned.
 */
@BatchKey("id")
@Serializable
data class Sprint(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("board_id")
    @Contextual
    val boardId: UUID,
    val name: String,
    val goal: String? = null,
    val state: SprintState = SprintState.FUTURE,
    @ColumnName("start_date")
    @Contextual
    val startDate: OffsetDateTime? = null,
    @ColumnName("end_date")
    @Contextual
    val endDate: OffsetDateTime? = null,
    @ColumnName("complete_date")
    @Contextual
    val completeDate: OffsetDateTime? = null,
    @ColumnName("committed_task_ids")
    val committedTaskIds: List<@Contextual UUID> = emptyList(),
    @ColumnName("added_during_sprint_task_ids")
    val addedDuringSprintTaskIds: List<@Contextual UUID> = emptyList(),
    @ColumnName("velocity_points")
    val velocityPoints: Double? = null,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("modified_at")
    @Contextual
    val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
    val version: Long = 0,
)

/** Input for creating a [Sprint]. */
@Serializable
data class CreateSprintInput(
    @Contextual
    val boardId: UUID,
    val name: String,
    val goal: String? = null,
)

/** Input for starting a sprint. */
@Serializable
data class StartSprintInput(
    @Contextual
    val sprintId: UUID,
    val expectedVersion: Long,
    @Contextual
    val startDate: OffsetDateTime? = null,
    @Contextual
    val endDate: OffsetDateTime? = null,
    val committedTaskIds: List<@Contextual UUID> = emptyList(),
)

/**
 * One entry in [CloseSprintInput.destinations]. `targetSprintId`
 * null means "drop to the project's backlog" — the task's sprintId
 * is cleared but the task stays in its project.
 */
@Serializable
data class CloseSprintDestination(
    @Contextual
    val taskId: UUID,
    @Contextual
    val targetSprintId: UUID? = null,
)

/**
 * Input for closing a sprint. The list-of-pairs shape is the
 * GraphQL-native form; the service converts to a `Map` for O(1)
 * lookup against the sprint's task snapshot.
 */
@Serializable
data class CloseSprintInput(
    @Contextual
    val sprintId: UUID,
    val expectedVersion: Long,
    val destinations: List<CloseSprintDestination> = emptyList(),
    val velocityPoints: Double? = null,
)

package bosca.workops.ui

import bosca.workops.ui.graphql.IWorkOpsSprintFields
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.OffsetDateTime

/** The inspected sprint lifecycle snapshot used by lists, details and mutation dialogs. */
@Serializable
data class WorkOpsSprintRecord(
    val id: String,
    val name: String,
    val state: String,
    val goal: String?,
    val startDate: String?,
    val endDate: String?,
    val completeDate: String?,
    val committedIds: List<String>,
    val addedIds: List<String>,
    val velocity: Double?,
    val boardId: String,
    val boardName: String,
    val boardType: String,
    val version: Long,
    val created: String,
    val modified: String,
) {
    val memberIds get() = (committedIds + addedIds).distinct()
    val href get() = "/workops/sprints/${workOpsEncode(id)}"
    val boardHref get() = "/workops/boards/${workOpsEncode(boardId)}?tab=sprints"
    val tone get() = when (state) { "ACTIVE" -> "success"; "FUTURE" -> "purple"; else -> "neutral" }
    fun remaining(now: OffsetDateTime = OffsetDateTime.now()): String {
        if (state != "ACTIVE" || endDate == null) return ""
        val days = kotlin.math.floor(Duration.between(now.toInstant(), OffsetDateTime.parse(endDate).toInstant()).seconds / 86_400.0).toLong()
        return when { days < 0 -> "${-days}d overdue"; days == 0L -> "Ends today"; else -> "${days}d left" }
    }
}

internal fun IWorkOpsSprintFields.sprintRecord() = WorkOpsSprintRecord(id, name, state.name, goal, startDate, endDate, completeDate,
    committedTaskIds, addedDuringSprintTaskIds, velocityPoints, boardId, board.name, board.type.name, version, createdAt, modifiedAt)

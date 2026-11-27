package bosca.workops.ui

import bosca.workops.ui.graphql.IWorkOpsSprintTaskFields
import kotlinx.serialization.Serializable

/** An authorized task summary or an explicitly unavailable member of a retained sprint snapshot. */
@Serializable
data class WorkOpsSprintTask(
    val id: String,
    val key: String = "",
    val summary: String = "",
    val version: Long = 0,
    val sprintId: String? = null,
    val status: String = "",
    val category: String = "",
    val priority: String = "",
    val priorityColor: String = "",
    val assigneeId: String? = null,
    val assigneeName: String = "",
    val added: Boolean = false,
    val available: Boolean = true,
) {
    val label get() = if (available) workOpsContentLabel(summary, id, "Task") else "Task unavailable"
    val href get() = if (available) "/workops/tasks/${workOpsEncode(key)}" else ""
    val finished get() = category in setOf("DONE", "CANCELLED")
    val tone get() = when (category) { "DONE" -> "success"; "IN_PROGRESS" -> "purple"; else -> "neutral" }
    val assigneeLabel get() = when { !available -> "Details unavailable"; assigneeId == null -> "Unassigned"; assigneeName.isBlank() -> "Profile unavailable"; else -> assigneeName }
}

internal fun IWorkOpsSprintTaskFields.sprintTask(added: Boolean = false) = WorkOpsSprintTask(id, key, summary, version, sprintId,
    status.name, status.category.name, priority.name, priority.colorHex, assigneeProfileId, assignee?.name.orEmpty(), added)

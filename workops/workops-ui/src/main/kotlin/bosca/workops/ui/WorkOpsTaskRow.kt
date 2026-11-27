package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Task search presentation under the current caller's entity permissions. */
@Serializable
data class WorkOpsTaskRow(
    val id: String,
    val key: String,
    val summary: String,
    val status: String,
    val statusCategory: String,
    val priority: String,
    val priorityColor: String,
    val taskType: String,
    val assignee: String,
    val dueDate: String,
    val assigneeProfileId: String?,
) {
    val assigneeLabel: String get() = workOpsAssigneeLabel(assigneeProfileId, assignee)
    val href: String get() = "/workops/tasks/${workOpsEncode(id)}"
    val statusTone: String
        get() = when (statusCategory) {
            "IN_PROGRESS" -> "purple"
            "DONE" -> "success"
            else -> "neutral"
        }
}

package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsFilterPreviewTask(val id: String, val key: String, val summary: String, val status: String, val category: String) {
    val href get() = "/workops/tasks/${workOpsEncode(id)}"
    val tone get() = when (category) { "DONE" -> "success"; "IN_PROGRESS" -> "purple"; else -> "neutral" }
}

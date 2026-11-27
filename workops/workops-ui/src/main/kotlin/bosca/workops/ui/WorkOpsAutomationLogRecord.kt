package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Execution facts retain the API's timestamps, outcome and nullable measured duration. */
@Serializable
data class WorkOpsAutomationLogRecord(
    val id: String,
    val outcome: String,
    val started: String,
    val finished: String = "",
    val duration: Long? = null,
    val error: String = "",
    val taskId: String = "",
) {
    val tone get() = when (outcome) { "OK" -> "success"; "SKIPPED" -> "neutral"; "NOT_IMPLEMENTED" -> "warning"; else -> "error" }
    val taskHref get() = if (taskId.isBlank()) "" else "/workops/tasks/${workOpsEncode(taskId)}"
}

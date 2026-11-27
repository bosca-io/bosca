package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsNotificationEventRecord(val key: String, val recipients: List<String>, val available: Boolean = true) {
    val label get() = workOpsNotificationLabel(key)
}

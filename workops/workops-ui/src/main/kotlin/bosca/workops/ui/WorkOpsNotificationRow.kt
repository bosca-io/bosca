package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsNotificationRow(val id: String, val event: String, val body: String, val href: String, val read: Boolean, val created: String) {
    val label get() = workOpsNotificationLabel(event)
}

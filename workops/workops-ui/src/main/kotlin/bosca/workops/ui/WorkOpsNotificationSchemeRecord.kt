package bosca.workops.ui

import bosca.workops.model.notification.NotificationEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class WorkOpsNotificationSchemeRecord(val id: String, val name: String, val description: String, val recipients: JsonElement) {
    val available get() = recipients is JsonObject
    val events get(): List<WorkOpsNotificationEventRecord> {
        val values = recipients as? JsonObject
        val keys = (NotificationEvent.entries.map { it.name } + values?.keys.orEmpty()).distinct()
        return keys.map { key ->
            val value = values?.get(key)
            WorkOpsNotificationEventRecord(key, (value as? JsonArray).orEmpty().map { recipient ->
                ((recipient as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank) ?: "Recipient unavailable"
            }, values != null && (value == null || value is JsonArray))
        }
    }
}

package bosca.workops.ui

import bosca.workops.model.notification.NotificationChannel
import bosca.workops.model.notification.NotificationEvent
import kotlinx.serialization.json.*

/** Preferences use the shared schema renderer; WorkOps owns its event and channel vocabulary. */
val workOpsNotificationSchema: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        for (key in listOf("watchAuthored", "watchCommented", "dailyDigest")) putJsonObject(key) { put("type", "boolean") }
        for (key in listOf("dndStartLocal", "dndEndLocal")) putJsonObject(key) { put("type", "string") }
        putJsonObject("flags") {
            put("type", "object"); putJsonObject("properties") {
                for (event in NotificationEvent.entries) putJsonObject(event.name) {
                    put("type", "object"); putJsonObject("properties") {
                        for (channel in NotificationChannel.entries) putJsonObject(channel.name) { put("type", "boolean") }
                    }
                }
            }
        }
    }
}

val workOpsNotificationUiSchema: JsonObject = buildJsonObject {
    put("version", 1)
    putJsonArray("layout") {
        add(buildJsonObject { put("type", "tabs"); putJsonArray("children") {
            add(buildJsonObject { put("type", "tab"); put("label", "Watch settings"); putJsonArray("children") {
                add(notificationField("watchAuthored", "switch", "Watch tasks I create"))
                add(notificationField("watchCommented", "switch", "Watch tasks I comment on"))
            } })
            add(buildJsonObject { put("type", "tab"); put("label", "Delivery channels"); putJsonArray("children") {
                add(buildJsonObject { put("type", "display"); put("control", "help-text"); put("text", "In-app and email are on by default. Webhook and Slack require a configured delivery pipeline.") })
                for (event in NotificationEvent.entries) add(buildJsonObject {
                    put("type", "section"); put("label", workOpsNotificationLabel(event.name))
                    putJsonArray("children") { add(buildJsonObject { put("type", "row"); putJsonArray("children") {
                        for (channel in NotificationChannel.entries) add(notificationField("flags.${event.name}.${channel.name}", "switch", notificationChannelLabel(channel.name), 3))
                    } }) }
                })
            } })
            add(buildJsonObject { put("type", "tab"); put("label", "Digest and quiet hours"); putJsonArray("children") {
                add(notificationField("dailyDigest", "switch", "Daily digest email"))
                add(notificationField("dndStartLocal", "text-input", "Do not disturb start (HH:mm)"))
                add(notificationField("dndEndLocal", "text-input", "Do not disturb end (HH:mm)"))
                add(buildJsonObject { put("type", "display"); put("control", "help-text"); put("text", "Use 24-hour local time, such as 22:00 to 08:00. Leave both fields empty to turn quiet hours off.") })
            } })
        } })
    }
}

fun workOpsNotificationLabel(event: String): String = when (event) {
    "TASK_COMMENTED" -> "Task comment added"
    "TASK_COMMENT_EDITED" -> "Task comment edited"
    "TASK_COMMENT_DELETED" -> "Task comment deleted"
    "WORKLOG_LOGGED" -> "Work log added"
    "WORKLOG_UPDATED" -> "Work log updated"
    "SPEC_COMMENTED" -> "Specification comment added"
    "SPEC_TASKS_GENERATED" -> "Specification tasks generated"
    "REQUIREMENT_COMMENTED" -> "Requirement comment added"
    else -> event.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercaseChar).replace("Sla ", "SLA ").replace("Worklog", "Work log")
}

private fun notificationChannelLabel(channel: String) = when (channel) { "IN_APP" -> "In-app"; "EMAIL" -> "Email"; "WEBHOOK" -> "Webhook"; else -> "Slack" }

private fun notificationField(property: String, control: String, label: String, columns: Int = 12) = buildJsonObject {
    put("type", "field"); put("property", property); put("control", control); put("label", label); put("col", columns)
}

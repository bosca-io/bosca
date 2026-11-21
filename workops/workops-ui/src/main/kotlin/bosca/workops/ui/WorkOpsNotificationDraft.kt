package bosca.workops.ui

import bosca.workops.model.notification.NotificationChannel
import bosca.workops.model.notification.NotificationEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Keeps hidden mute values and existing channel entries while the caller edits displayed preferences. */
@Serializable
data class WorkOpsNotificationDraft(
    val watchAuthored: Boolean = true,
    val watchCommented: Boolean = true,
    val dailyDigest: Boolean = false,
    val dndStartLocal: String = "",
    val dndEndLocal: String = "",
    val eventChannels: JsonObject = JsonObject(emptyMap()),
    val flags: Map<String, Map<String, Boolean>> = emptyMap(),
    val mutedTaskIds: List<String> = emptyList(),
    val mutedProjectIds: List<String> = emptyList(),
) {
    /** Missing entries retain the platform's in-app/email defaults; explicit empty arrays opt out. */
    fun channelsFor(event: String): Set<String> = (eventChannels[event] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: setOf("IN_APP", "EMAIL")

    fun withFlags() = copy(flags = NotificationEvent.entries.associate { event ->
        event.name to NotificationChannel.entries.associate { channel -> channel.name to (channel.name in channelsFor(event.name)) }
    })

    /** Updates only changed displayed event choices, retaining untouched and unknown entries. */
    fun updatedChannels(): JsonObject {
        val updated = eventChannels.toMutableMap()
        for (event in NotificationEvent.entries) {
            val previous = channelsFor(event.name)
            val enabled = NotificationChannel.entries.map { it.name }.filter { flags[event.name]?.get(it) ?: (it in previous) }.toSet()
            val previousVisible = previous.intersect(NotificationChannel.entries.map { it.name }.toSet())
            if (enabled != previousVisible) updated[event.name] = JsonArray(enabled.map(::JsonPrimitive))
        }
        return JsonObject(updated)
    }
}

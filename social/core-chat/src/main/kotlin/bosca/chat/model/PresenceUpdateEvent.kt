package bosca.chat.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Broadcast event indicating that a user's presence status has changed
 * (e.g., online, away, offline), delivered to subscribers of the channel.
 */
@Serializable
data class PresenceUpdateEvent(
    val profileId: UUID,
    val status: String
)

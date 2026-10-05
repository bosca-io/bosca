package bosca.chat.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Broadcast event indicating that a user has started or stopped typing
 * in a specific chat channel, used for real-time typing indicators.
 */
@Serializable
data class UserTypingEvent(
    val channelId: UUID,
    val profileId: UUID,
    val isTyping: Boolean
)

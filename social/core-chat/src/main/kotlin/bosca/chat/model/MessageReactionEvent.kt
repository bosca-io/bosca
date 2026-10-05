package bosca.chat.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Real-time event emitted when an emoji reaction is added to or removed
 * from a message in a channel. Clients use this to keep reaction counts
 * up to date without re-querying the message list.
 */
@Serializable
data class MessageReactionEvent(
    @Contextual
    val channelId: UUID,
    val sequence: Long,
    @Contextual
    val profileId: UUID,
    val emoji: String,
    /** `true` when the reaction was added, `false` when removed. */
    val added: Boolean,
)

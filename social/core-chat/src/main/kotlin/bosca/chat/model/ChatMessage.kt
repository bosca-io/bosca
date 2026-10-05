package bosca.chat.model

import bosca.db.annotation.ColumnName
import bosca.communications.model.MessageContent
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A single message within a chat channel. Messages are ordered by a monotonically
 * increasing [sequence] number assigned by the underlying message store (NATS JetStream).
 * Threading is supported via [parentSequence] which points to the root message of a thread.
 */
@Serializable
data class ChatMessage(
    val sequence: Long,
    @Contextual
    val timestamp: OffsetDateTime,
    @ColumnName("sender_id")
    @Contextual
    val senderId: UUID,
    @ColumnName("client_id")
    @Contextual
    val clientId: UUID = UUID.NIL,
    val content: List<MessageContent>,
    val attributes: JsonElement? = null,
    @ColumnName("parent_sequence")
    val parentSequence: Long? = null,
    val reactions: List<MessageReaction> = emptyList(),
    val deleted: Boolean = false,
)

/**
 * An emoji reaction left on a chat message by a user profile.
 */
@Serializable
data class MessageReaction(
    val emoji: String,
    @Contextual
    val profileId: UUID,
    /** Stable identifier used to retrieve this reaction without copying its emoji into notifications. */
    @Contextual
    val id: UUID = UUID.NIL,
)

/**
 * A real-time event payload delivered when a message is sent or updated
 * in a chat channel, carrying the channel identifier alongside the message.
 */
@Serializable
data class ChatMessageEvent(
    val channelId: UUID,
    val message: ChatMessage
)

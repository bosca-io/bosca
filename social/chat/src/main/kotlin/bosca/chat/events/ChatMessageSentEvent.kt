package bosca.chat.events

import bosca.communications.model.MessageContent
import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val CHAT_MESSAGE_SENT_TOPIC = "bosca.chat.v1.message.sent"

/**
 * Generic event dispatched whenever a message is sent through the shared
 * chat service, regardless of which feature module triggered it. Other
 * modules (community, collaboration) listen to this event and apply their
 * own filtering before dispatching domain-specific actions.
 */
@JobEvent(
    jobs = [],
    pubsubChannel = CHAT_MESSAGE_SENT_TOPIC,
    displayName = "Chat Message Sent",
    description = "Fires when a profile sends a message to a chat channel.",
)
@Serializable
data class ChatMessageSentEvent(
    @Contextual
    val channelId: UUID,
    @Contextual
    val senderId: UUID,
    val sequence: Long,
    val content: List<MessageContent>,
    @Contextual val sentAt: OffsetDateTime,
    val attributes: JsonElement? = null,
) : Event

package bosca.chat.events

import bosca.chat.model.ChatChannelType
import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

const val CHAT_CHANNEL_CREATED_TOPIC = "bosca.chat.v1.channel.created"

/**
 * Generic event dispatched whenever a chat channel is created through the
 * shared chat service. Listeners in feature modules (community, collaboration)
 * apply their own filtering — for example, community only acts on channels
 * with a non-null groupId.
 */
@JobEvent(jobs = [], pubsubChannel = CHAT_CHANNEL_CREATED_TOPIC)
@Serializable
data class ChatChannelCreatedEvent(
    @Contextual
    val channelId: UUID,
    @Contextual
    val groupId: UUID?,
    val name: String,
    val type: ChatChannelType
) : Event

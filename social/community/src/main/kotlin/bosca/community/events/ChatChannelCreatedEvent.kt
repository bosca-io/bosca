package bosca.community.events

import bosca.community.jobs.ChatChannelJoinAgentJob
import bosca.community.model.ChatChannelType
import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

const val CHAT_CHANNEL_CREATED_TOPIC = "bosca.community.v1.channel.created"

@JobEvent(
    jobs = [ChatChannelJoinAgentJob::class],
    pubsubChannel = CHAT_CHANNEL_CREATED_TOPIC
)
@Serializable
data class ChatChannelCreatedEvent(
    val channelId: UUID,
    val groupId: UUID?,
    val name: String,
    val type: ChatChannelType
) : Event

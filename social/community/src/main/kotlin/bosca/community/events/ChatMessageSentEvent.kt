package bosca.community.events

import bosca.community.jobs.ChatMessageAgentJob
import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.communications.model.MessageContent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@JobEvent(jobs = [ChatMessageAgentJob::class])
@Serializable
data class ChatMessageSentEvent(
    @Contextual
    val channelId: UUID,
    @Contextual
    val senderId: UUID,
    val sequence: Long,
    val content: List<MessageContent>,
    val attributes: JsonElement? = null,
) : Event

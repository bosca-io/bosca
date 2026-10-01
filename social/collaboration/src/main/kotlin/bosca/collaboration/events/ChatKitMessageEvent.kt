package bosca.collaboration.events

import bosca.collaboration.jobs.ChatKitAgentJob
import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.communications.model.MessageContent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Dispatched when a chat message contains an @kit mention or /kit slash command,
 * triggering the Kit AI agent to process and respond in the channel.
 */
@JobEvent(jobs = [ChatKitAgentJob::class])
@Serializable
data class ChatKitMessageEvent(
    @Contextual
    val channelId: UUID,
    @Contextual
    val senderId: UUID,
    val sequence: Long,
    val content: List<MessageContent>,
    val slashCommand: String? = null,
    val attributes: JsonElement? = null,
) : Event

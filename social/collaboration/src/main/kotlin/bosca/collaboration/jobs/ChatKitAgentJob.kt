package bosca.collaboration.jobs

import bosca.communications.model.MessageContent
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Job payload for invoking Kit (the full AI agent) in response to an @kit
 * mention or /kit slash command in a chat channel.
 */
@Serializable
data class ChatKitAgentJob(
    @Contextual
    val channelId: UUID,
    @Contextual
    val senderId: UUID,
    val sequence: Long,
    val content: List<MessageContent>,
    val slashCommand: String? = null,
    val attributes: JsonElement? = null,
) : IJobDefinition

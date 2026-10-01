package bosca.community.jobs

import bosca.communications.model.MessageContent
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatMessageAgentJob(
    @Contextual
    val channelId: UUID,
    @Contextual
    val senderId: UUID,
    val sequence: Long,
    val content: List<MessageContent>,
    val attributes: JsonElement? = null,
) : IJobDefinition

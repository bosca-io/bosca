package bosca.collaboration.jobs

import bosca.collaboration.bridge.BridgePlatform
import bosca.communications.model.MessageContent
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Job payload for relaying a Bosca chat message to external platforms
 * (Slack, Teams) via bridge bindings on the channel.
 */
@Serializable
data class BridgeOutboundJob(
    @Contextual
    val channelId: UUID,
    @Contextual
    val senderId: UUID,
    val sequence: Long,
    val content: List<MessageContent>,
    val senderName: String,
    val attributes: JsonElement? = null,
) : IJobDefinition

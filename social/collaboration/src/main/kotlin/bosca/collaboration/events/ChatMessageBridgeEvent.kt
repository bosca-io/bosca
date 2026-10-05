package bosca.collaboration.events

import bosca.collaboration.jobs.BridgeOutboundJob
import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.communications.model.MessageContent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Dispatched when a message is sent in a Bosca channel that has active
 * bridge bindings. Triggers the outbound bridge job to relay the message
 * to external platforms. Not dispatched for messages originating from
 * a bridge (echo prevention via the `bridge` attribute).
 */
@JobEvent(jobs = [BridgeOutboundJob::class])
@Serializable
data class ChatMessageBridgeEvent(
    @Contextual
    val channelId: UUID,
    @Contextual
    val senderId: UUID,
    val sequence: Long,
    val content: List<MessageContent>,
    val senderName: String,
    val attributes: JsonElement? = null,
) : Event

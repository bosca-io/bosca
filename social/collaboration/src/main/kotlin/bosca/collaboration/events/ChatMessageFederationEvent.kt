package bosca.collaboration.events

import bosca.collaboration.federation.FederationSyncDirection
import bosca.collaboration.jobs.FederationOutboundJob
import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.communications.model.MessageContent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Dispatched when a chat message is sent in a channel that has at least one
 * outbound or bidirectional federation link. The federation outbound job
 * republishes the message on each linked peer's federation subject so the
 * remote channel mirrors it. Not dispatched for messages whose
 * `attributes.source` is `"federation"` — that's how echo loops between
 * peers are broken.
 */
@JobEvent(jobs = [FederationOutboundJob::class])
@Serializable
data class ChatMessageFederationEvent(
    @Contextual
    val channelId: UUID,
    @Contextual
    val senderId: UUID,
    val sequence: Long,
    val content: List<MessageContent>,
    val senderName: String,
    /**
     * The list of peer / remote-channel pairs the message should be relayed
     * to. Computed by the dispatch listener so the executor doesn't need to
     * re-query for federation links.
     */
    val targets: List<FederationTarget>,
    val attributes: JsonElement? = null,
) : Event

@Serializable
data class FederationTarget(
    @Contextual
    val peerId: UUID,
    @Contextual
    val remoteChannelId: UUID,
    val syncDirection: FederationSyncDirection,
)

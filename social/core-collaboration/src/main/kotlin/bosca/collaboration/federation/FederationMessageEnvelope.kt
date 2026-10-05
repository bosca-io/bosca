package bosca.collaboration.federation

import bosca.communications.model.MessageContent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The wire shape of a federated chat message exchanged between Bosca peers
 * over the federation NATS subject space. The originating peer ships its
 * own `peerId` and `channelId` so the receiving peer can route the message
 * to the linked local channel and resolve the remote sender to a ghost
 * profile.
 *
 * The origin sequence is carried as a stable message identity for inbound deduplication. The
 * receiving side still assigns its own local sequence when
 * [bosca.chat.service.ChatService.sendMessage] persists the relayed message.
 */
@Serializable
data class FederationMessageEnvelope(
    /** ID of the peer that originated the message. */
    @Contextual
    val originPeerId: UUID,
    /** ID of the originating channel on that peer (used for routing on the inbound side). */
    @Contextual
    val originChannelId: UUID,
    /** Local profile id of the sender on the originating peer. */
    @Contextual
    val originSenderId: UUID,
    /** Sequence assigned by the originating peer, used with the peer and channel ids for deduplication. */
    val originSequence: Long,
    /** Display name of the sender, used to render the ghost profile on the inbound side. */
    val senderName: String,
    val content: List<MessageContent>,
    /** Originating message attributes, with the federation source marker applied. */
    val attributes: JsonElement? = null,
)

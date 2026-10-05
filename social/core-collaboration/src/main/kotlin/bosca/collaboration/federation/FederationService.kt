package bosca.collaboration.federation

import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages federation between Bosca instances, enabling cross-instance
 * message synchronization via NATS leaf nodes. Peers are registered
 * with shared secrets, and channels are linked bilaterally.
 */
interface FederationService : Service {

    /** Retrieves all registered federation peers */
    suspend fun getPeers(): List<FederationPeer>

    /** Retrieves a specific peer by ID */
    suspend fun getPeer(id: UUID): FederationPeer?

    /** Registers a new federation peer with connection details */
    suspend fun registerPeer(name: String, natsUrl: String, apiUrl: String, sharedSecret: String): FederationPeer

    /** Deactivates a federation peer */
    suspend fun deactivatePeer(id: UUID)

    /** Links a local channel to a remote channel on a federated peer */
    suspend fun federateChannel(localChannelId: UUID, peerId: UUID, remoteChannelId: UUID, direction: FederationSyncDirection)

    /** Removes a federation link for a channel */
    suspend fun unfederateChannel(localChannelId: UUID, peerId: UUID)

    /** Retrieves all federated channel links for a local channel */
    suspend fun getFederatedChannels(localChannelId: UUID): List<FederatedChannel>

    /**
     * Returns every active federation link across all peers. Used by the
     * inbound listener at startup to know which subjects to subscribe to.
     */
    suspend fun getAllFederatedChannels(): List<FederatedChannel>

    /**
     * Resolves a remote profile to a local ghost profile, creating one
     * if it doesn't exist yet.
     * @return the local profile ID
     */
    suspend fun resolveOrCreateFederatedProfile(peerId: UUID, remoteProfileId: UUID, displayName: String, avatarUrl: String?): UUID

    /**
     * Sets or rotates the shared secret used to authenticate handshake and
     * control-plane requests with a peer. Pass `null` to clear the secret.
     */
    suspend fun setSharedSecret(peerId: UUID, secret: String?)

    /**
     * Returns the decrypted shared secret for a peer, or `null` if the peer
     * has no secret configured. Surfaced through the interface (rather than
     * only on the impl) so the inbound handshake route can authenticate
     * incoming peers without downcasting. Callers must treat the returned
     * string as a credential — never log it or send it over a non-TLS
     * channel.
     */
    suspend fun getSharedSecret(peerId: UUID): String?

    /** Starts NATS listeners for all active federated channel subscriptions */
    fun startFederationListeners()

    /**
     * Performs the bilateral handshake with a peer that is the prerequisite
     * for federating a channel. Calls the peer's `/api/v1/federation/handshake`
     * endpoint with the local instance's peer id, the local channel id, and
     * the remote channel id; on success records the local-side federation
     * link automatically.
     *
     * Authentication uses the peer's shared secret as a bearer token. The
     * peer is responsible for verifying that secret and recording the
     * inverse link on its side.
     *
     * @return `true` if the peer accepted the handshake and the local link
     *   was recorded; `false` otherwise (with the underlying failure
     *   logged at the implementation level).
     */
    suspend fun handshakeWithPeer(
        peerId: UUID,
        localChannelId: UUID,
        remoteChannelId: UUID,
        direction: FederationSyncDirection = FederationSyncDirection.BIDIRECTIONAL,
    ): Boolean

    /**
     * Inbound side of [handshakeWithPeer]: invoked by the federation
     * handshake REST route after it has authenticated the request using
     * the configured shared secret. Records the local-side federation
     * link without calling out to the peer (the peer is the initiator).
     *
     * @param originPeerId the initiator's peer id (matches an existing
     *   row in `chat_federation_peers`; used to look up the shared secret)
     * @param originChannelId the initiator's local channel id (becomes
     *   `remote_channel_id` on this side)
     * @param remoteChannelId the channel id on this instance that the
     *   initiator wants the link to terminate at (becomes `local_channel_id`)
     * @param direction the requested sync direction; this side flips it
     *   so an OUTBOUND request becomes an INBOUND link locally
     */
    suspend fun acceptHandshakeFromPeer(
        originPeerId: UUID,
        originChannelId: UUID,
        remoteChannelId: UUID,
        direction: FederationSyncDirection,
    )
}

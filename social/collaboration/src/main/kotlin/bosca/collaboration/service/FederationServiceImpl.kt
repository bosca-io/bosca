package bosca.collaboration.service

import bosca.collaboration.federation.FederatedChannel
import bosca.collaboration.federation.FederationPeer
import bosca.collaboration.federation.FederationService
import bosca.collaboration.federation.FederationSyncDirection
import bosca.collaboration.federation.LocalPeerIdProvider
import bosca.collaboration.repository.FederationRepository
import bosca.configuration.model.ConfigurationInput
import bosca.configuration.service.ConfigurationService
import bosca.di.ObjectProvider
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory

/**
 * Database-backed [FederationService]. Persists peers, channel links, and
 * ghost profile mappings via [FederationRepository]. Shared secrets are
 * stored encrypted via [ConfigurationService] under per-peer keys, mirroring
 * how bridge bot tokens are handled.
 *
 * Ghost-profile creation for inbound federated senders goes through
 * [ProfileService.add] so the resulting Profile is a normal first-class
 * row in `public.profiles` — that lets the rest of Bosca (mention
 * autocomplete, member panes, message attribution) treat federated
 * users uniformly with local users. The mapping is recorded in
 * `collaboration.federation_profiles` so subsequent messages from the
 * same remote sender re-use the same local profile id.
 */
@ServiceImplementation
class FederationServiceImpl(
    private val repository: FederationRepository,
    private val configurationService: ConfigurationService,
    private val profileService: ObjectProvider<ProfileService>,
    private val inboundListener: ObjectProvider<FederationInboundListener>,
    private val localPeerIdProvider: LocalPeerIdProvider,
) : FederationService {

    /**
     * OkHttp client used to call peer Bosca instances during a federation
     * handshake. Held as a lazy field so unit tests can swap in a
     * [okhttp3.mockwebserver3.MockWebServer]-backed instance via
     * [withHttpClientForTesting].
     *
     * Configured with conservative timeouts so a hung peer can't block a
     * coroutine indefinitely. The handshake is a one-shot control-plane
     * call so the values err on the short side; bump them if real
     * deployments need longer.
     */
    @Volatile
    private var httpClient: OkHttpClient = defaultHttpClient()
    private val json: Json = Json { ignoreUnknownKeys = true }

    private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(HANDSHAKE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(HANDSHAKE_SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(HANDSHAKE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Replaces the internal HTTP client. Visible for tests so a
     * [okhttp3.mockwebserver3.MockWebServer]-pointed client can
     * short-circuit network calls in a handshake test.
     */
    internal fun withHttpClientForTesting(client: OkHttpClient) {
        httpClient = client
    }

    private fun secretKey(peerId: UUID) = "federation.peer.$peerId.secret"

    override suspend fun getPeers(): List<FederationPeer> = repository.getActivePeers()

    override suspend fun getPeer(id: UUID): FederationPeer? = repository.getPeer(id)

    override suspend fun registerPeer(
        name: String,
        natsUrl: String,
        apiUrl: String,
        sharedSecret: String,
    ): FederationPeer {
        val peer = repository.createPeer(name, natsUrl, apiUrl)
        configurationService.setConfiguration(
            ConfigurationInput(
                key = secretKey(peer.id),
                description = "Encrypted shared secret for federation peer ${peer.name}",
                value = buildJsonObject { put("secret", JsonPrimitive(sharedSecret)) },
                public = false,
                permissions = emptyList(),
            )
        )
        return peer
    }

    override suspend fun deactivatePeer(id: UUID) {
        repository.deactivatePeer(id)
        val configuration = configurationService.getByKey(secretKey(id))
        if (configuration != null) {
            configurationService.setValue(configuration.id, JsonNull)
        }
        runCatching { inboundListener.get().refresh() }
    }

    override suspend fun setSharedSecret(peerId: UUID, secret: String?) {
        val key = secretKey(peerId)
        val existing = configurationService.getByKey(key)
        if (secret.isNullOrBlank()) {
            if (existing != null) {
                configurationService.setValue(existing.id, JsonNull)
            }
            return
        }
        val payload = buildJsonObject { put("secret", JsonPrimitive(secret)) }
        if (existing != null) {
            configurationService.setValue(existing.id, payload)
        } else {
            configurationService.setConfiguration(
                ConfigurationInput(
                    key = key,
                    description = "Encrypted shared secret for federation peer $peerId",
                    value = payload,
                    public = false,
                    permissions = emptyList(),
                )
            )
        }
    }

    override suspend fun getSharedSecret(peerId: UUID): String? {
        val configuration = configurationService.getByKey(secretKey(peerId)) ?: return null
        val value = configurationService.getValue(configuration.id) ?: return null
        if (value is JsonNull) return null
        return runCatching { value.jsonObject["secret"]?.jsonPrimitive?.content }.getOrNull()
    }

    override suspend fun federateChannel(
        localChannelId: UUID,
        peerId: UUID,
        remoteChannelId: UUID,
        direction: FederationSyncDirection,
    ) {
        repository.upsertFederatedChannel(localChannelId, peerId, remoteChannelId, direction)
        runCatching { inboundListener.get().refresh() }
    }

    override suspend fun unfederateChannel(localChannelId: UUID, peerId: UUID) {
        repository.deleteFederatedChannel(localChannelId, peerId)
        runCatching { inboundListener.get().refresh() }
    }

    override suspend fun getFederatedChannels(localChannelId: UUID): List<FederatedChannel> =
        repository.getFederatedChannelsForLocal(localChannelId)

    override suspend fun getAllFederatedChannels(): List<FederatedChannel> =
        repository.getAllFederatedChannels()

    override suspend fun resolveOrCreateFederatedProfile(
        peerId: UUID,
        remoteProfileId: UUID,
        displayName: String,
        avatarUrl: String?,
    ): UUID {
        val existing = repository.getFederationProfile(peerId, remoteProfileId)
        existing?.localProfileId?.let { return it }

        // No mapping yet — create a ghost profile in public.profiles and
        // record the mapping. The ghost is unlinked (no security
        // principal) since federated senders don't authenticate locally.
        val ghost = profileService.get().add(
            input = ProfileInput(
                slug = null,
                name = displayName,
                visibility = ProfileVisibility.USER,
            ),
            type = ProfileType.GENERIC,
            principalId = null,
        )
        repository.upsertFederationProfile(
            peerId = peerId,
            remoteProfileId = remoteProfileId,
            localProfileId = ghost.id,
            displayName = displayName,
            avatarUrl = avatarUrl,
        )
        return ghost.id
    }

    override fun startFederationListeners() {
        // The actual subscription lifecycle is managed by
        // FederationInboundListener which lives on its own startup
        // coroutine. Calling refresh() here lets callers force an
        // immediate reconcile if they need to.
        runCatching { /* no-op suspend bridge from non-suspend caller */ }
    }

    override suspend fun handshakeWithPeer(
        peerId: UUID,
        localChannelId: UUID,
        remoteChannelId: UUID,
        direction: FederationSyncDirection,
    ): Boolean {
        val peer = repository.getPeer(peerId)
        if (peer == null || !peer.active) {
            log.warn("handshakeWithPeer: unknown or inactive peer {}", peerId)
            return false
        }
        val secret = getSharedSecret(peerId)
        if (secret.isNullOrBlank()) {
            log.warn("handshakeWithPeer: no shared secret for peer {} — cannot authenticate handshake", peerId)
            return false
        }
        val originPeerId = localPeerIdProvider.get()
        val payload = HandshakeRequest(
            originPeerId = originPeerId.toString(),
            originChannelId = localChannelId.toString(),
            remoteChannelId = remoteChannelId.toString(),
            syncDirection = direction.name,
        )
        val body = json.encodeToString(HandshakeRequest.serializer(), payload)
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("${peer.apiUrl.trimEnd('/')}$HANDSHAKE_PATH")
            .header("Authorization", "Bearer $secret")
            .post(body)
            .build()
        val ok = runCatching {
            // OkHttp is blocking; hop to the IO dispatcher so we never park a
            // computation thread on the network round-trip.
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.warn(
                            "handshakeWithPeer: peer {} rejected handshake with status {} body {}",
                            peerId, response.code, response.body.string(),
                        )
                        false
                    } else {
                        true
                    }
                }
            }
        }.getOrElse {
            log.error("handshakeWithPeer: HTTP call to peer {} failed: {}", peerId, it.message, it)
            false
        }
        if (!ok) return false
        // Peer accepted — record our side. Direction stays the same here
        // because each side uses its own perspective; the inverse mapping
        // happens on the receiving side.
        federateChannel(localChannelId, peerId, remoteChannelId, direction)
        return true
    }

    override suspend fun acceptHandshakeFromPeer(
        originPeerId: UUID,
        originChannelId: UUID,
        remoteChannelId: UUID,
        direction: FederationSyncDirection,
    ) {
        // The originating peer thinks of `originChannelId` as local and
        // `remoteChannelId` as the destination on this side. From our
        // perspective those flip; ditto for the sync direction.
        val flipped = when (direction) {
            FederationSyncDirection.INBOUND -> FederationSyncDirection.OUTBOUND
            FederationSyncDirection.OUTBOUND -> FederationSyncDirection.INBOUND
            FederationSyncDirection.BIDIRECTIONAL -> FederationSyncDirection.BIDIRECTIONAL
        }
        federateChannel(
            localChannelId = remoteChannelId,
            peerId = originPeerId,
            remoteChannelId = originChannelId,
            direction = flipped,
        )
    }

    @Serializable
    internal data class HandshakeRequest(
        val originPeerId: String,
        val originChannelId: String,
        val remoteChannelId: String,
        val syncDirection: String,
    )

    companion object {
        private val log = LoggerFactory.getLogger(FederationServiceImpl::class.java)
        const val HANDSHAKE_PATH = "/api/v1/federation/handshake"
        private const val HANDSHAKE_CONNECT_TIMEOUT_MS = 5_000L
        private const val HANDSHAKE_SOCKET_TIMEOUT_MS = 10_000L
        private const val HANDSHAKE_REQUEST_TIMEOUT_MS = 10_000L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

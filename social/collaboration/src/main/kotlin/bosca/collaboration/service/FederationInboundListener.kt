package bosca.collaboration.service

import bosca.chat.service.ChatService
import bosca.collaboration.federation.FederatedChannel
import bosca.collaboration.federation.FederationMessageEnvelope
import bosca.collaboration.federation.FederationService
import bosca.collaboration.federation.FederationSyncDirection
import bosca.di.ObjectProvider
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

/**
 * Federation subject convention. The originating peer publishes on
 * `bosca.federation.chat.{originChannelId}.messages`; receiving peers
 * subscribe to that exact subject for each remote channel they have
 * a federation link to.
 */
fun federationSubject(channelId: UUID): String = "bosca.federation.chat.$channelId.messages"

/**
 * Owns the per-channel inbound federation subscriptions. On startup it
 * walks every active federation link with a BIDIRECTIONAL or INBOUND sync
 * direction and opens a long-lived subscription; new links can be picked
 * up via [refresh] (called from the federation admin mutations).
 *
 * Each inbound message is deserialized, its sender is mapped to a local
 * ghost profile (creating one on first contact), and the message is
 * persisted by calling [ChatService.sendMessage] with the federation
 * source marker so the dispatch listener doesn't echo it back out.
 */
class FederationInboundListener(
    private val pubSubService: PubSubService,
    private val federationServiceProvider: ObjectProvider<FederationService>,
    private val chatService: ObjectProvider<ChatService>,
    @Suppress("unused") private val profileService: ObjectProvider<ProfileService>,
) {

    private suspend fun federationService(): FederationService = federationServiceProvider.get()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = mutableMapOf<SubscriptionKey, Job>()

    init {
        scope.launch {
            // Defer subscription startup so the rest of DI is resolved by
            // the time we go reading from the federated_channels table.
            delay(STARTUP_DELAY_MS)
            runCatching { refresh() }
                .onFailure { log.error("Initial federation listener startup failed", it) }
        }
    }

    /**
     * Reconciles open inbound subscriptions with the current set of
     * federation links. Called on startup and whenever a federation link
     * is added or removed (the federation mutations call this directly
     * via the service interface).
     */
    suspend fun refresh() {
        val desired = collectInboundLinks().map { SubscriptionKey(it.peerId, it.remoteChannelId, it.localChannelId) }.toSet()

        // Cancel subscriptions for links that were removed.
        val toCancel = activeJobs.keys - desired
        for (key in toCancel) {
            activeJobs.remove(key)?.cancel()
        }

        // Start subscriptions for links that didn't have one.
        for (key in desired) {
            if (activeJobs.containsKey(key)) continue
            activeJobs[key] = scope.launch { runSubscription(key) }
        }
    }

    private suspend fun collectInboundLinks(): List<FederatedChannel> {
        return federationService().getAllFederatedChannels()
            .filter { it.syncDirection != FederationSyncDirection.OUTBOUND }
    }

    private suspend fun runSubscription(key: SubscriptionKey) {
        while (true) {
            try {
                pubSubService.subscribe(federationSubject(key.remoteChannelId), FederationMessageEnvelope.serializer())
                    .collect { msg ->
                        runCatching { handleInbound(key, msg.message) }
                            .onFailure { log.error("Federation inbound delivery failed for {} -> {}: {}", key.remoteChannelId, key.localChannelId, it.message, it) }
                    }
            } catch (e: Exception) {
                log.error("Federation subscription on {} failed, retrying in 5s", key.remoteChannelId, e)
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /**
     * Persists an inbound federated message into the linked local channel
     * after attributing it to a ghost profile and applying the federation
     * source marker for echo prevention.
     */
    internal suspend fun handleInbound(key: SubscriptionKey, envelope: FederationMessageEnvelope) {
        val localSender = federationService().resolveOrCreateFederatedProfile(
            peerId = key.peerId,
            remoteProfileId = envelope.originSenderId,
            displayName = envelope.senderName,
            avatarUrl = null,
        )
        val attributes = mergeFederationAttributes(envelope.attributes, key.peerId, envelope.originChannelId)
        chatService.get().sendMessage(
            channelId = key.localChannelId,
            senderId = localSender,
            clientId = clientIdForEnvelope(envelope),
            content = envelope.content,
            attributes = attributes,
        )
    }

    /**
     * Adds the federation source marker (and origin metadata) to the
     * incoming attributes so the dispatch listener knows not to ship it
     * back out to peers.
     */
    internal fun mergeFederationAttributes(original: kotlinx.serialization.json.JsonElement?, peerId: UUID, originChannelId: UUID): JsonObject {
        return buildJsonObject {
            (original as? JsonObject)?.forEach { (k, v) -> put(k, v) }
            put("source", JsonPrimitive("federation"))
            put("originPeerId", JsonPrimitive(peerId.toString()))
            put("originChannelId", JsonPrimitive(originChannelId.toString()))
        }
    }

    /** Produces the same local deduplication id whenever a peer redelivers one origin message. */
    internal fun clientIdForEnvelope(envelope: FederationMessageEnvelope): UUID {
        val key = "federation:${envelope.originPeerId}:${envelope.originChannelId}:${envelope.originSequence}"
        return UUID.parse(
            java.util.UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString()
        )
    }

    /** A stable key for tracking one inbound subscription per (peer, remote, local) triple. */
    internal data class SubscriptionKey(val peerId: UUID, val remoteChannelId: UUID, val localChannelId: UUID)

    companion object {
        private val log = LoggerFactory.getLogger(FederationInboundListener::class.java)
        const val STARTUP_DELAY_MS = 5_000L
        const val RETRY_DELAY_MS = 5_000L
    }
}

package bosca.communications.service

import bosca.bml.message.client.EmailLinkClicked
import bosca.bml.message.client.EmailOpened
import bosca.cache.withRequestCache
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatusType
import bosca.db.withConnectionManager
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Subscribes to the BML Message Server's first-party email engagement events (its public `/c` and `/o`
 * tracking routes publish [EmailLinkClicked]/[EmailOpened]) and records them as CLICKED/OPENED
 * [DeliveryEvent]s — the same ledger the SendGrid webhook feeds, so per-recipient delivery
 * history reads identically regardless of which tracker observed the engagement.
 *
 * Events without a recipient id (bulk sends) are skipped: [DeliveryEvent] is a per-recipient
 * record, and aggregate click/open rates are the analytics pipeline's job, which the email
 * message server feeds directly.
 */
class EmailEngagementListener(
    private val pubSubService: PubSubService,
    private val deliveryTracking: DeliveryTrackingService,
) {

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Default)

    init {
        subscribe(EmailLinkClicked.CHANNEL, EmailLinkClicked.serializer()) { event ->
            record(event.messageId, event.recipientId, DeliveryStatusType.CLICKED, event.url, event.id)
        }
        subscribe(EmailOpened.CHANNEL, EmailOpened.serializer()) { event ->
            record(event.messageId, event.recipientId, DeliveryStatusType.OPENED, url = null, event.id)
        }
    }

    private fun <T> subscribe(channel: String, deserializer: DeserializationStrategy<T>, handle: suspend (T) -> Unit) {
        scope.launch {
            while (true) {
                try {
                    pubSubService.subscribe(channel, deserializer).collect { msg ->
                        // Establish the request-scoped context (cache + DB connection) the
                        // repositories under DeliveryTrackingService need — the standard
                        // PubSub-listener wrapping (see AgentGitPushListener).
                        withRequestCache {
                            withConnectionManager {
                                handle(msg.message)
                            }
                        }
                    }
                    log.warn("email engagement listener ended on {}, retrying in 5s", channel)
                    delay(5000.milliseconds)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("email engagement listener failed on {}, retrying in 5s: {}", channel, e.message)
                    delay(5000.milliseconds)
                }
            }
        }
    }

    /** Stops both engagement subscriptions and waits for them to finish. */
    suspend fun shutdown() {
        scopeJob.cancelAndJoin()
    }

    /**
     * Single-event recording, internal so tests drive it without the subscription loop. A bad
     * event (unparseable ids, storage failure) logs and is dropped — engagement is best-effort
     * telemetry and must never wedge the listener.
     */
    internal suspend fun record(
        messageId: String,
        recipientId: String?,
        status: DeliveryStatusType,
        url: String?,
        eventId: String,
    ) {
        val message = parse(messageId) ?: run {
            log.warn("email engagement {} with unparseable message id '{}' dropped", status, messageId)
            return
        }
        val recipient = recipientId?.let(::parse) ?: return
        try {
            deliveryTracking.recordEvent(
                DeliveryEvent(
                    providerEventId = eventId,
                    messageId = message,
                    recipientId = recipient,
                    status = status,
                    providerEvent = if (status == DeliveryStatusType.CLICKED) "click" else "open",
                    metadata = buildJsonObject {
                        put("source", "bml-message-server")
                        url?.let { put("url", it) }
                    },
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("recording email engagement {} for message {} failed: {}", status, messageId, e.message, e)
        }
    }

    private fun parse(id: String): UUID? = try {
        UUID.parse(id)
    } catch (_: Exception) {
        null
    }

    companion object {
        private val log = LoggerFactory.getLogger(EmailEngagementListener::class.java)
    }
}

package bosca.analytics.queue

import bosca.analytics.model.Events
import bosca.analytics.service.EventProcessingHealth
import bosca.nats.NatsConnectionPool
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamOptions
import io.nats.client.PublishOptions
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StreamConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Durable JetStream handoff from collector enrichment to processor transforms and storage. */
class NatsAnalyticsEventQueue(
    private val nats: NatsConnectionPool,
    private val json: Json,
    private val health: EventProcessingHealth,
) : AnalyticsEventQueue {

    @Volatile
    private var js: Pair<Connection, JetStream>? = null
    private val initMutex = Mutex()

    override suspend fun enqueue(events: Events) {
        try {
            publish(events)
            health.queueRecovered()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            health.queueFailed()
            throw bosca.analytics.repository.nats.NatsPublishException(e)
        }
    }

    private suspend fun publish(events: Events) {
        val jetStream = ensureInitialized()
        val futures = withContext(Dispatchers.IO) {
            val messageIdPrefix = eventIdentityPrefix(events.context)
            events.events.map { event ->
                val message = events.copy(events = listOf(event))
                val payload = json.encodeToString(Events.serializer(), message)
                // Without an installation or event ID, deduplication could discard unrelated events.
                val clientId = event.clientId
                val messageId = if (messageIdPrefix != null && !clientId.isNullOrBlank()) "$messageIdPrefix$clientId" else null
                val options = PublishOptions.builder().messageId(messageId).build()
                jetStream.publishAsync(STREAM_SUBJECT, payload.toByteArray(Charsets.UTF_8), options)
                    // A cancelled NATS future is a publish failure, not coroutine cancellation.
                    .exceptionally { throw IOException("Failed to publish analytics event to NATS", it) }
            }
        }
        for (future in futures) {
            future.await()
        }
    }

    private val consumerMutex = Mutex()
    private var consumer: io.nats.client.JetStreamSubscription? = null
    private var consumerConnection: Connection? = null

    override suspend fun receive(timeout: kotlin.time.Duration): AnalyticsEventDelivery? = receiveBatch(1, timeout).firstOrNull()

    override suspend fun receiveBatch(limit: Int, timeout: kotlin.time.Duration): List<AnalyticsEventDelivery> {
        require(limit > 0)
        return withContext(Dispatchers.IO) {
            subscription().fetch(limit, Duration.ofMillis(timeout.inWholeMilliseconds.coerceAtLeast(1)))
                .map { NatsAnalyticsEventDelivery(it, json) }
        }
    }
    internal suspend fun subscription(): io.nats.client.JetStreamSubscription = consumerMutex.withLock {
            val connection = nats.systemConnection()
            if (consumerConnection !== connection) {
                consumer?.unsubscribe()
                ensureStream(connection.jetStreamManagement())
                consumer = connection.jetStream().subscribe(STREAM_SUBJECT, io.nats.client.PullSubscribeOptions.builder().configuration(
                    io.nats.client.api.ConsumerConfiguration.builder().durable("analytics-event-processor").ackWait(Duration.ofMinutes(2)).build()
                ).build())
                consumerConnection = connection
            }
            checkNotNull(consumer)
        }

    override suspend fun checkReadiness() {
        // withTimeoutOrNull keeps a slow JetStream distinct from caller cancellation.
        withTimeoutOrNull(READINESS_TIMEOUT) {
            val connection = nats.systemConnection()
            runInterruptible(Dispatchers.IO) {
                val options = JetStreamOptions.builder().requestTimeout(Duration.ofSeconds(1)).build()
                ensureStream(connection.jetStreamManagement(options))
            }
        } ?: throw IOException("NATS readiness check timed out after $READINESS_TIMEOUT")
    }
    override suspend fun status(): AnalyticsEventQueueStatus = withContext(Dispatchers.IO) {
        val state = nats.systemConnection().jetStreamManagement().getStreamInfo(STREAM_NAME).streamState
        AnalyticsEventQueueStatus(pending = state.msgCount, storageBytes = state.byteCount)
    }
    override suspend fun shutdown() { consumerMutex.withLock { consumer?.unsubscribe(); consumer = null; consumerConnection = null } }

    private suspend fun ensureInitialized(): JetStream {
        val connection = nats.systemConnection()
        js?.takeIf { it.first === connection }?.let { return it.second }
        return initMutex.withLock {
            js?.takeIf { it.first === connection }?.let { return@withLock it.second }
            withContext(Dispatchers.IO) {
                ensureStream(connection.jetStreamManagement())
                connection.jetStream().also { js = connection to it }
            }
        }
    }

    companion object {
        const val STREAM_NAME = "analytics-events-strm"
        const val STREAM_SUBJECT = "analytics.events-strm"
        private val READINESS_TIMEOUT = 2.seconds

        private val log = LoggerFactory.getLogger(NatsAnalyticsEventQueue::class.java)

        /**
         * Creates the JetStream stream for analytics event forwarding if it does
         * not already exist. This is the single source of truth for stream
         * configuration — both the publisher ([enqueue]) and consumer
         * ([bosca.analytics.service.AnalyticsEventConsumer]) call this method.
         */
        fun ensureStream(jsm: io.nats.client.JetStreamManagement) {
            try {
                jsm.getStreamInfo(STREAM_NAME)
            } catch (e: JetStreamApiException) {
                if (e.apiErrorCode == 10059) {
                    log.info("Creating JetStream stream '{}' for analytics event forwarding", STREAM_NAME)
                    jsm.addStream(
                        StreamConfiguration.builder()
                            .name(STREAM_NAME)
                            .subjects(STREAM_SUBJECT)
                            .retentionPolicy(RetentionPolicy.WorkQueue)
                            .build()
                    )
                } else {
                    throw e
                }
            }
        }
    }
}

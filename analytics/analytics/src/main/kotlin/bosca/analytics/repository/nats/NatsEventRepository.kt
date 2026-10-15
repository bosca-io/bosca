package bosca.analytics.repository.nats

import bosca.analytics.model.Events
import bosca.analytics.repository.EventRepository
import bosca.analytics.service.EventProcessingHealth
import bosca.nats.NatsConnectionPool
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamOptions
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

/**
 * [EventRepository] implementation that publishes processed analytics events to a
 * NATS JetStream stream instead of writing them to a local data store.
 *
 * Designed for the GraalVM native analytics collector, where events have already
 * passed through the collector's transform chain (e.g., geo-enrichment) but cannot
 * be stored locally or processed by dynamic scripting. The analytics-processor
 * subscribes to this stream, applies its own transform chain (e.g., script
 * transforms and triggers), and writes the final events to Iceberg.
 */
class NatsEventRepository(
    private val nats: NatsConnectionPool,
    private val json: Json,
    private val health: EventProcessingHealth,
) : EventRepository {

    @Volatile
    private var js: Pair<Connection, JetStream>? = null
    private val initMutex = Mutex()

    override suspend fun process(events: Events) {
        try {
            publish(events)
            health.natsRecovered()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            health.natsFailed()
            throw e
        }
    }

    private suspend fun publish(events: Events) {
        val jetStream = ensureInitialized()
        val futures = withContext(Dispatchers.IO) {
            events.events.map { event ->
                val message = events.copy(events = listOf(event))
                val payload = json.encodeToString(Events.serializer(), message)
                jetStream.publishAsync(STREAM_SUBJECT, payload.toByteArray(Charsets.UTF_8))
                    // A cancelled NATS future is a publish failure, not coroutine cancellation.
                    .exceptionally { throw IOException("Failed to publish analytics event to NATS", it) }
            }
        }
        for (future in futures) {
            future.await()
        }
    }

    override suspend fun flush() {
        // Events are published to NATS immediately on process(), nothing to flush
    }

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

        private val log = LoggerFactory.getLogger(NatsEventRepository::class.java)

        /** Checks JetStream independently of incoming events so readiness can recover after traffic stops. */
        suspend fun checkReadiness(nats: NatsConnectionPool, health: EventProcessingHealth) {
            try {
                val ready = withTimeoutOrNull(2.seconds) {
                    val connection = nats.systemConnection()
                    runInterruptible(Dispatchers.IO) {
                        val options = JetStreamOptions.builder().requestTimeout(Duration.ofSeconds(1)).build()
                        ensureStream(connection.jetStreamManagement(options))
                    }
                    true
                } == true
                if (ready) health.natsRecovered() else health.natsFailed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                health.natsFailed()
                log.warn("NATS readiness check failed", e)
            }
        }

        /**
         * Creates the JetStream stream for analytics event forwarding if it does
         * not already exist. This is the single source of truth for stream
         * configuration — both the publisher ([NatsEventRepository]) and consumer
         * ([bosca.analytics.service.NatsEventConsumer]) call this method.
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

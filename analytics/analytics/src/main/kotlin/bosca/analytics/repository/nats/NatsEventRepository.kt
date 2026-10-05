package bosca.analytics.repository.nats

import bosca.analytics.model.Events
import bosca.analytics.repository.EventRepository
import bosca.nats.NatsConnectionPool
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StreamConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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
) : EventRepository {

    @Volatile
    private var js: JetStream? = null
    private val initMutex = Mutex()

    override suspend fun process(events: Events) {
        val jetStream = ensureInitialized()
        val futures = withContext(Dispatchers.IO) {
            events.events.map { event ->
                val message = events.copy(events = listOf(event))
                val payload = json.encodeToString(Events.serializer(), message)
                jetStream.publishAsync(STREAM_SUBJECT, payload.toByteArray(Charsets.UTF_8))
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
        js?.let { return it }
        return initMutex.withLock {
            js?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val connection = nats.systemConnection()
                ensureStream(connection.jetStreamManagement())
                connection.jetStream().also { js = it }
            }
        }
    }

    companion object {
        const val STREAM_NAME = "analytics-events-strm"
        const val STREAM_SUBJECT = "analytics.events-strm"

        private val log = LoggerFactory.getLogger(NatsEventRepository::class.java)

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

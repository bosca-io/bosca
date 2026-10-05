package bosca.analytics.service

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.Events
import bosca.analytics.repository.EventRepository
import bosca.analytics.repository.nats.NatsEventRepository
import bosca.cache.withRequestCache
import bosca.db.withConnectionManager
import bosca.nats.NatsConnectionPool
import bosca.server.Headers
import io.nats.client.JetStream
import io.nats.client.JetStreamSubscription
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.ConsumerConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Consumes analytics event batches from the NATS JetStream stream populated
 * by [NatsEventRepository] and runs the processor-side transform chain before
 * writing events to a local [EventRepository] (typically Iceberg).
 *
 * The collector's transform chain handles static enrichment (e.g., geo-location
 * from Cloudflare headers) and publishes the enriched events to NATS. This
 * consumer picks them up and applies dynamic transforms that require a full JVM
 * runtime — script transforms, script triggers, and any other processing that
 * cannot run inside a GraalVM native image.
 *
 * Each message is acknowledged only after the full pipeline completes
 * successfully. On failure the message is NAK'd so NATS can redeliver it.
 */
class NatsEventConsumer(
    private val nats: NatsConnectionPool,
    private val json: Json,
    private val eventRepository: EventRepository,
    private val eventTransforms: EventPipelineTransforms,
    private val config: EventProcessingConfiguration,
) {

    /**
     * Runs the analytics event consumer loop, pulling messages from the NATS
     * JetStream stream and dispatching them to a fixed pool of worker coroutines
     * via a bounded [Channel] for backpressure. Suspends indefinitely until the
     * coroutine is cancelled.
     */
    suspend fun run() {
        val (_, subscription) = initializeJetStream()
        log.info("Analytics event consumer started, listening on '{}'", NatsEventRepository.STREAM_SUBJECT)

        val channel = Channel<io.nats.client.Message>(capacity = config.channelCapacity)

        coroutineScope {
            repeat(config.workerCount) {
                launch(Dispatchers.IO) {
                    for (message in channel) {
                        processMessage(message)
                    }
                }
            }

            withContext(Dispatchers.IO) {
                while (true) {
                    try {
                        val messages = subscription.fetch(config.natsBatchSize, Duration.ofSeconds(config.natsFetchTimeoutSeconds))
                        for (message in messages) {
                            channel.send(message)
                        }
                    } catch (_: CancellationException) {
                        break
                    } catch (e: Exception) {
                        log.error("Error fetching messages from NATS: {}", e.message, e)
                    }
                }
            }

            channel.close()
        }

        log.info("Analytics event consumer stopped")
    }

    internal suspend fun processMessage(message: io.nats.client.Message) = withRequestCache {
        withConnectionManager {
            try {
                val payload = message.data.toString(Charsets.UTF_8)
                var events = json.decodeFromString(Events.serializer(), payload)
                val context = EventPipelineContext(Headers.Empty)
                // A transform failure propagates to the outer catch below, which NAKs the message so
                // NATS redelivers it — rather than silently storing an un-transformed batch. The whole
                // chain re-runs on redelivery, so transforms must be idempotent (see EventPipelineTransform).
                for (transform in eventTransforms.transforms) {
                    events = transform.transform(context, events)
                }
                eventRepository.process(events)
                message.ack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to process analytics event message: {}", e.message, e)
                message.nak()
            }
        }
    }

    internal suspend fun initializeJetStream(): Pair<JetStream, JetStreamSubscription> {
        return withContext(Dispatchers.IO) {
            val connection = nats.systemConnection()
            NatsEventRepository.ensureStream(connection.jetStreamManagement())
            val js = connection.jetStream()
            val consumerConfig = ConsumerConfiguration.builder()
                .durable(CONSUMER_NAME)
                .ackWait(Duration.ofMinutes(2))
                .build()
            val subscription = js.subscribe(
                NatsEventRepository.STREAM_SUBJECT,
                PullSubscribeOptions.builder().configuration(consumerConfig).build()
            )
            js to subscription
        }
    }

    companion object {
        private const val CONSUMER_NAME = "analytics-event-processor"

        private val log = LoggerFactory.getLogger(NatsEventConsumer::class.java)
    }
}

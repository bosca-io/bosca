package bosca.analytics.service

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.queue.NatsAnalyticsEventDelivery
import bosca.analytics.queue.NatsAnalyticsEventQueue
import bosca.analytics.repository.EventRepository
import bosca.nats.NatsConnectionPool
import io.nats.client.Message
import kotlinx.serialization.json.Json

/** Connects real or mocked JetStream deliveries to the production transport-neutral consumer. */
internal class NatsConsumerTestFixture(
    private val nats: NatsConnectionPool,
    private val json: Json,
    repository: EventRepository,
    transforms: EventPipelineTransforms,
    config: EventProcessingConfiguration,
) {
    private val queue = NatsAnalyticsEventQueue(nats, json, EventProcessingHealth())
    private val consumer = AnalyticsEventConsumer(queue, repository, transforms, config)
    suspend fun initializeJetStream() = nats.systemConnection().jetStream() to queue.subscription()
    suspend fun processMessage(message: Message) = consumer.processDelivery(NatsAnalyticsEventDelivery(message, json))
    suspend fun processMessages(messages: List<Message>) = consumer.processDeliveries(messages.map { NatsAnalyticsEventDelivery(it, json) })
    suspend fun run() = consumer.run()
}

package bosca.analytics.queue

import bosca.analytics.model.Events
import io.nats.client.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Duration

internal class NatsAnalyticsEventDelivery(private val message: Message, private val json: Json) : AnalyticsEventDelivery {
    override val batches: List<Events> get() = listOf(json.decodeFromString(Events.serializer(), message.data.toString(Charsets.UTF_8)))
    override suspend fun acknowledge(): Unit = withContext(Dispatchers.IO) { message.ackSync(Duration.ofSeconds(5)) }
    override suspend fun retry(delay: kotlin.time.Duration) { message.nakWithDelay(Duration.ofMillis(delay.inWholeMilliseconds.coerceAtLeast(1))) }
    override suspend fun renew() { message.inProgress() }
}

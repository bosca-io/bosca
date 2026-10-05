package bosca.pubsub

import bosca.nats.NatsConnectionPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration

class NatsPubSubServiceImpl @OptIn(ExperimentalSerializationApi::class) constructor(
    private val json: Json,
    private val nats: NatsConnectionPool,
) : PubSubService {

    override suspend fun <T> publish(channel: String, serializer: SerializationStrategy<T>, message: T) {
        val messageString = json.encodeToString(serializer, message)
        val connection = nats.openConnection(channel)
        try {
            connection.publish(channel, messageString.toByteArray(Charsets.UTF_8))
        } finally {
            connection.close()
        }
    }

    override fun <T> subscribe(channel: String, deserializer: DeserializationStrategy<T>): Flow<Message<T>> =
        subscribe(channel, deserializer, onSubscribed = null)

    internal fun <T> subscribe(
        channel: String,
        deserializer: DeserializationStrategy<T>,
        onSubscribed: (() -> Unit)?,
    ): Flow<Message<T>> = flow {
        val connection = nats.openConnection()
        try {
            emitAll(
                connection.subscribe(channel) {
                    if (onSubscribed != null) {
                        connection.flush(SUBSCRIPTION_READY_TIMEOUT)
                        onSubscribed()
                    }
                }.mapNotNull { message ->
                    try {
                        Message(
                            channel = message.subject,
                            message = json.decodeFromString(
                                deserializer,
                                message.data.toString(Charsets.UTF_8),
                            ),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Ignoring malformed NATS message on subject '{}'", message.subject, e)
                        null
                    }
                },
            )
        } finally {
            connection.close()
        }
    }.retryWhen { cause, attempt ->
        if (cause is CancellationException) return@retryWhen false
        val delayMs = ((attempt + 1).coerceAtMost(10)) * RETRY_DELAY_MS
        log.warn("NATS subscription on '{}' failed; retrying in {}ms", channel, delayMs, cause)
        delay(delayMs)
        true
    }

    companion object {
        private const val RETRY_DELAY_MS = 100L
        private val SUBSCRIPTION_READY_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val log = LoggerFactory.getLogger(NatsPubSubServiceImpl::class.java)
    }
}

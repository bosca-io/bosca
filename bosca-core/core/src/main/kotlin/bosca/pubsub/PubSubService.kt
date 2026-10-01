package bosca.pubsub

import bosca.service.Service
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

data class Message<T>(
    val channel: String,
    val message: T
)

/**
 * Provides publish/subscribe messaging capabilities over named channels.
 *
 * Implementations handle the underlying transport (e.g., Redis, NATS) and serialize/deserialize
 * messages using kotlinx.serialization strategies.
 */
interface PubSubService : Service {

    /**
     * Publishes a message to the specified channel, serializing it with the given [serializer].
     *
     * @param T the message type
     * @param channel the channel name to publish to
     * @param serializer the serialization strategy for encoding the message
     * @param message the message payload to publish
     */
    suspend fun <T> publish(channel: String, serializer: SerializationStrategy<T>, message: T)

    /**
     * Subscribes to the specified channel and returns a [Flow] of incoming messages,
     * deserializing each one with the given [deserializer].
     *
     * The returned flow is cold and will begin receiving messages when collected.
     *
     * @param T the message type
     * @param channel the channel name to subscribe to
     * @param deserializer the deserialization strategy for decoding incoming messages
     * @return a flow of [Message] instances received on the channel
     */
    fun <T> subscribe(channel: String, deserializer: DeserializationStrategy<T>): Flow<Message<T>>
}
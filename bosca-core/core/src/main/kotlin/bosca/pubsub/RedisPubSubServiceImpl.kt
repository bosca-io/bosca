package bosca.pubsub

import bosca.redis.RedisConnectionPool
import bosca.redis.SharedConnection
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndDecrement
import kotlin.concurrent.atomics.fetchAndIncrement

class RedisPubSubServiceImpl @OptIn(ExperimentalSerializationApi::class) constructor(
    private val json: Json,
    connections: RedisConnectionPool,
) : PubSubService {

    private val sharedConnection = SharedConnection(connections)

    @OptIn(ExperimentalAtomicApi::class)
    private val subscriptions = mutableMapOf<String, AtomicInt>()
    private val mutex = Mutex()

    @OptIn(ExperimentalLettuceCoroutinesApi::class, ExperimentalSerializationApi::class)
    override suspend fun <T> publish(channel: String, serializer: SerializationStrategy<T>, message: T) {
        val pubSubConnection = sharedConnection.pubSubConnection()
        val messageBytes = json.encodeToString(serializer, message)
        pubSubConnection
            .coroutines()
            .publish(channel, messageBytes)
    }

    @OptIn(ExperimentalSerializationApi::class, ExperimentalAtomicApi::class)
    override fun <T> subscribe(channel: String, deserializer: DeserializationStrategy<T>) =
        flow {
            val connection = sharedConnection.pubSubConnection()
            val subscription = connection.reactive()
            val count = mutex.withLock { subscriptions.getOrPut(channel) { AtomicInt(0) } }
            try {
                if (count.fetchAndIncrement() == 0) {
                    subscription.subscribe(channel).awaitFirstOrNull()
                }
                while (currentCoroutineContext().isActive) {
                    subscription.observeChannels().asFlow().filter { it.channel == channel }.collect {
                        val message = json.decodeFromString(deserializer, it.message)
                        emit(
                            Message(
                                channel = it.channel,
                                message = message
                            )
                        )
                    }
                }
            } finally {
                if (count.fetchAndDecrement() == 1) {
                    subscription.unsubscribe(channel).awaitFirstOrNull()
                }
            }
        }
}
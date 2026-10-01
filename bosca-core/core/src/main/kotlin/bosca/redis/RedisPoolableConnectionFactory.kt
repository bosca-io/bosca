package bosca.redis

import bosca.pool.PoolableConnectionFactory
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.coroutines
import io.lettuce.core.codec.StringCodec
import kotlinx.coroutines.future.await

class RedisPoolableConnectionFactory(
    private val client: RedisClient,
    private val uri: RedisURI,
    override val maxConnections: Int = 50,
) : PoolableConnectionFactory<RedisConnection> {

    override suspend fun create(): RedisConnection {
        return client.connectAsync(StringCodec(), uri).await()
    }

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    override suspend fun validate(connection: RedisConnection): Boolean {
        return try {
            connection.coroutines().ping() == "PONG"
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun destroy(connection: RedisConnection) {
        try {
            connection.closeAsync().await()
        } catch (_: Exception) {
        }
    }

    override fun isAlive(connection: RedisConnection): Boolean {
        return connection.isOpen
    }
}

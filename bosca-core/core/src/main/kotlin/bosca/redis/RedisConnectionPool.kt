package bosca.redis

import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.pool.GenericConnectionPool
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.future.await
import kotlin.reflect.KClass

typealias RedisConnection = StatefulRedisConnection<String, String>
typealias RedisPubSubConnection = StatefulRedisPubSubConnection<String, String>

class RedisConnectionPool private constructor(
    host: String,
    port: Int,
    database: Int,
    private val pubSubCodec: RedisCodec<String, String>,
    maxConnections: Int,
) {

    private val uri = RedisURI.Builder.redis(host, port).withDatabase(database).build()
    private val client = RedisClient.create(uri)
    private val pool = GenericConnectionPool(
        factory = RedisPoolableConnectionFactory(client, uri, maxConnections),
        name = "redis-pool",
    )

    constructor(host: String, port: Int, maxConnections: Int = 50) : this(
        host = host,
        port = port,
        database = 0,
        pubSubCodec = StringCodec(),
        maxConnections = maxConnections,
    )

    /**
     * Creates a pool isolated to one logical database and Pub/Sub channel namespace.
     *
     * Ordinary data uses the logical database so Lua scripts and data-structure members retain their
     * exact production representation. The namespace applies to Pub/Sub channels, whose server-side
     * scope otherwise crosses logical databases.
     */
    constructor(
        host: String,
        port: Int,
        database: Int,
        namespace: String,
        maxConnections: Int = 50,
    ) : this(
        host = host,
        port = port,
        database = database,
        pubSubCodec = if (namespace.isBlank()) StringCodec() else NamespacedStringCodec(namespace),
        maxConnections = maxConnections,
    )

    suspend fun newPubSubConnection(): RedisPubSubConnection {
        return client.connectPubSubAsync(pubSubCodec, uri).await()
    }

    suspend fun connection(): RedisConnection = pool.obtain()

    suspend fun release(connection: RedisConnection) = pool.release(connection)

    /** Closes the connection pool and shuts down the underlying Redis client. */
    suspend fun close() {
        runCatching {
            pool.close()
        }
        runCatching {
            client.shutdown()
        }
    }

    companion object {

        @OptIn(InternalDI::class)
        fun register(host: String, port: Int, maxConnections: Int = 50) {
            ProviderRegistry.register(RedisConnectionPool::class, object : ObjectProvider<RedisConnectionPool> {
                override val type: KClass<RedisConnectionPool> = RedisConnectionPool::class
                override suspend fun get(): RedisConnectionPool {
                    return RedisConnectionPool(host, port, maxConnections)
                }
            }, true)
        }

        /**
         * Registers a pool isolated to a logical database and Pub/Sub namespace. A blank [namespace] leaves
         * Pub/Sub channels unprefixed, so they are shared with every client of the Redis server.
         */
        @OptIn(InternalDI::class)
        fun register(host: String, port: Int, database: Int, namespace: String, maxConnections: Int = 50) {
            require(database >= 0) { "Redis database must be non-negative" }
            ProviderRegistry.register(RedisConnectionPool::class, object : ObjectProvider<RedisConnectionPool> {
                override val type: KClass<RedisConnectionPool> = RedisConnectionPool::class
                override suspend fun get(): RedisConnectionPool = RedisConnectionPool(host, port, database, namespace, maxConnections)
            }, true)
        }
    }
}

private class NamespacedStringCodec(namespace: String) : RedisCodec<String, String> {
    private val delegate = StringCodec()
    private val prefix = "$namespace:"

    override fun decodeKey(bytes: java.nio.ByteBuffer): String = delegate.decodeKey(bytes).removePrefix(prefix)

    override fun decodeValue(bytes: java.nio.ByteBuffer): String = delegate.decodeValue(bytes)

    override fun encodeKey(key: String): java.nio.ByteBuffer = delegate.encodeKey(prefix + key)

    override fun encodeValue(value: String): java.nio.ByteBuffer = delegate.encodeValue(value)
}

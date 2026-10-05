package bosca.cache

import bosca.cache.nats.NatsCacheManager
import bosca.cache.redis.RedisCacheManager
import bosca.cache.redis.RedisCacheScripts
import bosca.nats.NatsConnectionPool
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedValkeyContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * A production [CacheManager] over a shared test-resource backend. `redis` uses Valkey with the production default
 * pool of 50 connections and the production script settings; `nats` uses JetStream KeyValue buckets.
 */
class BenchmarkCacheBackend(backend: String) {

    private var valkey: SharedValkeyContainer? = null
    private var redisScope: CoroutineScope? = null
    private var nats: SharedNatsContainer? = null
    private var natsPool: NatsConnectionPool? = null

    val manager: CacheManager = when (backend) {
        REDIS -> {
            val container = SharedValkeyContainer().also { it.start() }
            valkey = container
            val pool = container.newConnectionPool(REDIS_MAX_CONNECTIONS)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { redisScope = it }
            RedisCacheManager(pool, RedisCacheScripts(pool, publishEvictions = false), scope)
        }
        NATS -> {
            val container = SharedNatsContainer().also { it.start() }
            nats = container
            NatsCacheManager(container.newConnectionPool().also { natsPool = it })
        }
        else -> error("Unknown cache backend: $backend")
    }

    /** Removes everything the benchmark stored, then releases the backend. */
    fun close() = runBlocking {
        try {
            natsPool?.let { pool ->
                // Dropping a bucket is one call; clear() deletes and purges every key individually.
                val management = pool.systemConnection().keyValueManagement()
                manager.cacheNames.forEach { management.delete(it.replace(':', '-')) }
            } ?: manager.clearAll()
        } finally {
            redisScope?.cancel()
            // Each shared-service fixture closes the pools it created.
            valkey?.stop()
            nats?.stop()
        }
    }

    companion object {
        const val REDIS = "redis"
        const val NATS = "nats"
        private const val REDIS_MAX_CONNECTIONS = 50
    }
}

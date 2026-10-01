package bosca.cache.nats

import bosca.cache.Cache
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.di.provide
import bosca.di.provides
import bosca.nats.NatsConnectionPool
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

class NatsCacheManager @OptIn(ExperimentalSerializationApi::class) constructor(
    private val pool: NatsConnectionPool
) : CacheManager {

    private val caches = ConcurrentHashMap<String, Cache<*>>()
    override val cacheNames: Set<String> get() = caches.keys

    private val createMutex = Mutex()

    override suspend fun <K> maybeAddCache(name: String, keySerializer: CacheKeySerializer<K>, expiration: Duration): Cache<K> {
        if (caches.containsKey(name)) return getCache(name)
        createMutex.withLock {
            if (caches.containsKey(name)) return getCache(name)
            val cache = create(name, keySerializer, expiration)
            caches[name] = cache
            return cache
        }
    }

    override suspend fun <K> getCache(name: String): Cache<K> {
        val cache = caches[name]
        @Suppress("UNCHECKED_CAST")
        return (cache ?: error("Cache $name not found")) as Cache<K>
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <K> create(name: String, keySerializer: CacheKeySerializer<K>, expiration: Duration): Cache<K> {
        return NatsCache.newCache(pool, name, expiration, keySerializer)
    }

    override suspend fun evictExpiredItems() {
        caches.values.forEach { it.evictExpiredItems() }
    }

    override suspend fun clearAll() {
        caches.values.forEach { it.clear() }
    }

    companion object {

        @OptIn(ExperimentalSerializationApi::class)
        fun register() {
            provides<CacheManager>(true) {
                val pool = provide<NatsConnectionPool>()
                NatsCacheManager(pool)
            }
        }
    }
}
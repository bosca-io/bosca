package bosca.cache.redis

import bosca.cache.Cache
import bosca.cache.CacheDispatcher
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.di.provide
import bosca.di.provides
import bosca.redis.RedisConnectionPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RedisCacheManager(
    private val connections: RedisConnectionPool,
    private val scripts: RedisCacheScripts,
    cleanupScope: CoroutineScope,
) : CacheManager {

    private val caches = ConcurrentHashMap<String, Cache<*>>()

    override val cacheNames: Set<String> get() = caches.keys

    private val createMutex = Mutex()

    private val cleanupJob = cleanupScope.launch(CacheDispatcher) {
        while (currentCoroutineContext().isActive) {
            delay(CLEANUP_INTERVAL_MILLIS.milliseconds)
            try {
                evictExpiredItems()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to evict expired Redis cache items: {}", e.message, e)
            }
        }
    }

    internal suspend fun shutdown() {
        cleanupJob.cancelAndJoin()
    }

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

    private fun <K> create(name: String, keySerializer: CacheKeySerializer<K>, expiration: Duration): Cache<K> {
//        NearCacheRedisCache needs scripts built with publishEvictions = true (see register).
//        return NearCacheRedisCache(connections, scripts, name, expiration, keySerializer)
        return RedisCache(connections, scripts, name, expiration, keySerializer)
    }

    override suspend fun evictExpiredItems() {
        var failure: Exception? = null
        for ((name, cache) in caches) {
            try {
                cache.evictExpiredItems()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cacheFailure = IllegalStateException("Failed to evict expired items from Redis cache $name", e)
                if (failure == null) {
                    failure = cacheFailure
                } else {
                    failure.addSuppressed(cacheFailure)
                }
            }
        }
        failure?.let { throw it }
    }

    override suspend fun clearAll() {
        caches.values.forEach { it.clear() }
    }

    companion object {

        private const val CLEANUP_INTERVAL_MILLIS = 30_000L
        private val log = LoggerFactory.getLogger(RedisCacheManager::class.java)

        fun register(cleanupScope: CoroutineScope) {
            provides<CacheManager>(true) {
                val connections = provide<RedisConnectionPool>()
                // Only NearCacheRedisCache consumes eviction publishes, and create() builds RedisCache.
                val scripts = RedisCacheScripts(connections, publishEvictions = false)
                RedisCacheManager(connections, scripts, cleanupScope)
            }
        }
    }
}

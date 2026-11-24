package bosca.cache.postgres

import bosca.cache.*
import bosca.db.ConnectionPool
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

class PostgresCacheManager(private val pool: ConnectionPool) : CacheManager {
    private val caches = ConcurrentHashMap<String, Cache<*>>()
    override val cacheNames: Set<String> get() = caches.keys
    override suspend fun <K> maybeAddCache(name: String, keySerializer: CacheKeySerializer<K>, expiration: Duration): Cache<K> {
        caches.computeIfAbsent(name) { PostgresCache(pool, name, keySerializer, expiration) }
        return getCache(name)
    }
    @Suppress("UNCHECKED_CAST")
    override suspend fun <K> getCache(name: String): Cache<K> = (caches[name] ?: error("Cache $name not found")) as Cache<K>
    override suspend fun evictExpiredItems() { caches.values.forEach { it.evictExpiredItems() } }
    override suspend fun clearAll() { caches.values.forEach { it.clear() } }
}

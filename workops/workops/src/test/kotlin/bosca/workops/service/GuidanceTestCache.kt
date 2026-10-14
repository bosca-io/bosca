@file:OptIn(ExperimentalUuidApi::class)

package bosca.workops.service

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi

/**
 * An in-memory [Cache] / [CacheManager] suitable for service integration tests.
 *
 * Backed by a shared `ConcurrentHashMap` per cache name, this implementation is a
 * faithful enough stand-in for the production Redis / NATS caches that
 * [bosca.cache.RequestCache] can exercise its full get / put / remove flow without
 * a running broker. Crucially, `get()` returns `exists = false` for unknown keys so
 * `ServiceCacheImpl` falls through to its batch resolver, which is what production
 * behaviour would ultimately do.
 */
internal class GuidanceTestCacheValue(override val value: String?, override val exists: Boolean) : CacheValue

internal class GuidanceTestCache<K>(override val keySerializer: CacheKeySerializer<K>) : Cache<K> {

    private val store = ConcurrentHashMap<String, GuidanceTestCacheValue>()

    override suspend fun get(key: CacheKey<K>): CacheValue {
        val k = key.toRemoteKey()
        return store[k] ?: GuidanceTestCacheValue(null, false)
    }

    override suspend fun getBatch(keys: List<CacheKey<K>>): List<CacheValue> = keys.map { get(it) }

    override suspend fun put(key: CacheKey<K>, value: String?) {
        store[key.toRemoteKey()] = GuidanceTestCacheValue(value, true)
    }

    override suspend fun putBatch(entries: List<Pair<CacheKey<K>, String?>>) {
        entries.forEach { (k, v) -> put(k, v) }
    }

    override suspend fun remove(key: CacheKey<K>, keyPrefix: Boolean): CacheValue? {
        val k = key.toRemoteKey()
        if (keyPrefix) {
            val prefix = key.toRemoteKeyPrefix()
            store.keys.filter { it.startsWith(prefix) }.forEach { store.remove(it) }
            return null
        }
        val old = store.remove(k)
        return old
    }

    override suspend fun clear() = store.clear()

    override suspend fun evictExpiredItems() = Unit

    override val estimatedSize: Long get() = store.size.toLong()
}

internal class GuidanceTestCacheManager : CacheManager {

    private val caches = ConcurrentHashMap<String, GuidanceTestCache<*>>()
    override val cacheNames: Set<String> get() = caches.keys.toSet()

    @Suppress("UNCHECKED_CAST")
    override suspend fun <K> maybeAddCache(name: String, keySerializer: CacheKeySerializer<K>, expiration: Duration): Cache<K> {
        return caches.computeIfAbsent(name) { GuidanceTestCache(keySerializer) } as Cache<K>
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <K> getCache(name: String): Cache<K> {
        return (caches[name] ?: throw IllegalStateException("Cache '$name' not registered")) as Cache<K>
    }

    override suspend fun evictExpiredItems() = Unit

    override suspend fun clearAll() {
        caches.values.forEach { it.clear() }
    }
}

package bosca.experimentation

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.StringCacheKey
import bosca.cache.asCoroutineContext
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import io.mockk.every
import io.mockk.mockkObject
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Duration

/**
 * Shared in-memory cache fixtures for the experimentation test module.
 *
 * Constructing `FeatureFlagServiceImpl` (and any other service that
 * holds a `ServiceCache`) calls `provide<CacheManager>()` from
 * `ServiceCache`'s factory at instance-init time, so any test that
 * touches one of these services has to register a CacheManager in the
 * DI provider registry before the service is constructed.
 *
 * The fixtures here mirror the in-memory cache pattern from
 * `RequestCacheTest` (the canonical example in core/src/test) and
 * extend it with:
 *
 *   - A real [RequestCacheSerializerImpl] backed by [Json], so cached
 *     values that need to round-trip across "requests" (i.e. across
 *     `withFlagCache` blocks) can serialize/deserialize via
 *     kotlinx.serialization rather than via toString.
 *   - [installInDi], which registers a [InMemoryCacheManager] with
 *     [ProviderRegistry] via mockkObject so that `provide<CacheManager>()`
 *     calls inside `ServiceCache(...)` factory blocks return it.
 *   - [withFlagCache], which establishes a `RequestCache` coroutine
 *     context for a single test block — analogous to a single HTTP
 *     request in production. Multiple `withFlagCache` blocks share the
 *     same underlying `InMemoryCache`, so cross-request hits exercise
 *     the remote tier exactly like Redis would in production.
 *
 * The shared `InMemoryCache` is plain enough to inspect from tests:
 * use [InMemoryCache.estimatedSize] or [InMemoryCache.containsKey]
 * to verify caching invariants directly.
 */
class FlagCacheTestSupport {

    private class SimpleCacheValue(
        override val value: String?,
        override val exists: Boolean,
    ) : CacheValue

    /**
     * Tiny multi-named in-memory [Cache] implementation. Backed by a
     * `cacheName -> (remoteKey -> serialized value)` map so a single
     * instance can serve every cache the service registers (flag,
     * running-experiment, active-keys) without juggling separate
     * instances per name.
     */
    class InMemoryCache(
        override val keySerializer: CacheKeySerializer<String>,
    ) : Cache<String> {

        private val store = mutableMapOf<String, MutableMap<String, String?>>()

        private fun bucket(key: CacheKey<String>) = store.getOrPut(key.cacheName) { mutableMapOf() }

        override suspend fun get(key: CacheKey<String>): CacheValue {
            val b = bucket(key)
            val k = key.toRemoteKey()
            return if (b.containsKey(k)) SimpleCacheValue(b[k], true)
            else SimpleCacheValue(null, false)
        }

        override suspend fun getBatch(keys: List<CacheKey<String>>): List<CacheValue> = keys.map { get(it) }

        override suspend fun put(key: CacheKey<String>, value: String?) {
            bucket(key)[key.toRemoteKey()] = value
        }

        override suspend fun putBatch(entries: List<Pair<CacheKey<String>, String?>>) {
            entries.forEach { (key, value) -> put(key, value) }
        }

        override suspend fun remove(key: CacheKey<String>, keyPrefix: Boolean): CacheValue? {
            val b = bucket(key)
            if (keyPrefix) {
                val prefix = key.toRemoteKeyPrefix()
                b.keys.filter { it.startsWith(prefix) }.toList().forEach { b.remove(it) }
                return null
            }
            val k = key.toRemoteKey()
            val old = b.remove(k)
            return if (old != null) SimpleCacheValue(old, true) else null
        }

        override suspend fun clear() {
            store.values.forEach { it.clear() }
        }

        override suspend fun evictExpiredItems() {}

        override val estimatedSize: Long
            get() = store.values.sumOf { it.size.toLong() }

        /** Number of entries currently held in the named cache bucket. */
        fun sizeOf(cacheName: String): Int = store[cacheName]?.size ?: 0
    }

    /**
     * In-memory [CacheManager] handing back the same shared
     * [InMemoryCache] regardless of cache name. Real production caches
     * are per-name, but for tests a single shared store is simpler and
     * the bucket map inside [InMemoryCache] keeps the namespaces
     * separate anyway.
     */
    /**
     * In-memory [CacheManager] that tracks per-name serializers
     * registered via [maybeAddCache]. Each cache name gets a
     * [TypedCacheWrapper] that delegates storage to the shared
     * [InMemoryCache] but uses the correct [CacheKeySerializer]
     * for key conversion, so UUID-keyed and String-keyed caches
     * coexist without ClassCastException.
     */
    class InMemoryCacheManager(val cache: InMemoryCache) : CacheManager {
        private val caches = mutableMapOf<String, Cache<*>>()

        override val cacheNames: Set<String>
            get() = caches.keys

        @Suppress("UNCHECKED_CAST")
        override suspend fun <K> maybeAddCache(
            name: String,
            keySerializer: CacheKeySerializer<K>,
            expiration: Duration,
        ): Cache<K> {
            return caches.getOrPut(name) {
                TypedCacheWrapper(cache, keySerializer)
            } as Cache<K>
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <K> getCache(name: String): Cache<K> {
            return (caches[name] ?: cache) as Cache<K>
        }

        override suspend fun evictExpiredItems() {}
        override suspend fun clearAll() = cache.clear()
    }

    /**
     * Wraps the shared [InMemoryCache] (String-keyed) with a typed
     * [CacheKeySerializer] so callers see `Cache<K>` with the
     * correct serializer for key conversion.
     */
    private class TypedCacheWrapper<K>(
        private val delegate: InMemoryCache,
        override val keySerializer: CacheKeySerializer<K>,
    ) : Cache<K> {
        private fun toStringKey(key: CacheKey<K>): CacheKey<String> =
            bosca.cache.StringCacheKey(key.cacheName, key.toRemoteKey())

        override suspend fun get(key: CacheKey<K>): CacheValue = delegate.get(toStringKey(key))
        override suspend fun getBatch(keys: List<CacheKey<K>>): List<CacheValue> = keys.map { get(it) }
        override suspend fun put(key: CacheKey<K>, value: String?) = delegate.put(toStringKey(key), value)
        override suspend fun putBatch(entries: List<Pair<CacheKey<K>, String?>>) {
            entries.forEach { (k, v) -> put(k, v) }
        }
        override suspend fun remove(key: CacheKey<K>, keyPrefix: Boolean): CacheValue? =
            delegate.remove(toStringKey(key), keyPrefix)
        override suspend fun clear() = delegate.clear()
        override suspend fun evictExpiredItems() {}
        override val estimatedSize: Long get() = delegate.estimatedSize
    }

    private val keySerializer = bosca.cache.serializers.StringKeySerializer
    val cache: InMemoryCache = InMemoryCache(keySerializer)
    val cacheManager: InMemoryCacheManager = InMemoryCacheManager(cache)
    val requestCacheSerializer: RequestCacheSerializer = RequestCacheSerializerImpl(Json)

    /**
     * Registers [cacheManager] as the resolved value of
     * `provide<CacheManager>()` and [requestCacheSerializer] as the
     * resolved value of `provide<RequestCacheSerializer>()`. Must be
     * called from `@BeforeTest` *before* constructing any service that
     * holds a `ServiceCache` field.
     */
    @OptIn(InternalDI::class)
    fun installInDi() {
        mockkObject(ProviderRegistry)
        every { ProviderRegistry.get(CacheManager::class) } returns object : ObjectProvider<CacheManager> {
            override val type = CacheManager::class
            override suspend fun get() = cacheManager
        }
        every { ProviderRegistry.get(RequestCacheSerializer::class) } returns object : ObjectProvider<RequestCacheSerializer> {
            override val type = RequestCacheSerializer::class
            override suspend fun get() = requestCacheSerializer
        }
        // The assignment writer job calls `withConnectionManager` which
        // resolves a ConnectionPool from DI. Provide a mock pool whose
        // connection() returns a relaxed ConnectionManager. The
        // extension function `asCoroutineContext()` on the real
        // ConnectionManager class works on the mock instance because
        // it just wraps `this` in a ConnectionManagerContext.
        val mockPool = io.mockk.mockk<bosca.db.ConnectionPool>()
        val mockConn = io.mockk.mockk<bosca.db.ConnectionManager>(relaxed = true)
        io.mockk.every { mockPool.connection() } returns mockConn
        every { ProviderRegistry.get(bosca.db.ConnectionPool::class) } returns object : ObjectProvider<bosca.db.ConnectionPool> {
            override val type = bosca.db.ConnectionPool::class
            override suspend fun get() = mockPool
        }
    }

    /**
     * Runs [block] inside a fresh `RequestCache` coroutine context,
     * mimicking a single HTTP request in production. The shared
     * underlying [cache] persists across `withFlagCache` invocations,
     * so a value put in one block is visible from another, exactly
     * like a Redis-backed cache shared across requests on a node.
     */
    suspend fun <T> withFlagCache(block: suspend () -> T): T {
        val rc = RequestCache(cacheManager, requestCacheSerializer)
        return withContext(rc.asCoroutineContext()) {
            block()
        }
    }
}

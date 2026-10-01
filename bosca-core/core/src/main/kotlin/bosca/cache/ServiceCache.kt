package bosca.cache

import bosca.cache.service.ServiceCacheImpl
import bosca.di.provide
import bosca.graphql.Batch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * High-level, typed cache facade that sits above the raw string-based [Cache].
 *
 * Automatically resolves cache misses via the configured resolver function and
 * integrates with GraphQL [Batch] for DataLoader-style batched lookups.
 *
 * @param K the application-level key type
 * @param BV the business value type stored and returned by this cache
 */
interface ServiceCache<K, BV> {

    /**
     * Returns the cached value for [key], resolving it via the configured resolver on a miss.
     *
     * @return the resolved value, or `null` if the resolver returns `null`
     */
    suspend fun get(key: K): BV?

    /** Explicitly stores [value] in the cache under [key]. */
    suspend fun put(key: K, value: BV)

    /**
     * Retrieves multiple values in bulk, resolving any misses individually.
     * Ordering matches the input [keys] list.
     */
    suspend fun getAll(keys: List<K>): List<BV?>

    /**
     * Populates a GraphQL [Batch] with cached values, using the batch resolver for any misses.
     * This enables DataLoader-style batched fetching within a single request.
     */
    suspend fun addToBatch(batch: Batch<K, BV>)

    /**
     * Removes the entry for [key]. When [keyPrefix] is `true`, removes all entries
     * whose key shares the same prefix.
     */
    suspend fun remove(key: K, keyPrefix: Boolean = false)

    /** Removes all entries from this cache. */
    suspend fun clear()
}

@Suppress("FunctionName")
fun <K, BV> ServiceCache(
    cacheName: String,
    serializer: CacheKeySerializer<K>,
    batchResolver: suspend (keys: List<K>, batch: Batch<K, BV>) -> Unit = { _, _ -> error("no batch support: $cacheName") },
    expiration: Duration = 10.minutes,
    resolver: suspend (key: K) -> BV?,
): ServiceCacheImpl<K, BV> {
    runBlocking {
        val cacheManager = provide<CacheManager>()
        cacheManager.maybeAddCache(cacheName, serializer, expiration)
    }
    return ServiceCacheImpl(
        cacheName,
        batchResolver,
        resolver
    )
}
package bosca.cache

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes


/**
 * Central registry for named [Cache] instances.
 *
 * Provides lifecycle operations (eviction, clearing) that apply across all managed caches.
 */
interface CacheManager {

    /** The set of cache names currently registered with this manager. */
    val cacheNames: Set<String>

    /**
     * Returns the cache for [name], creating and registering it with [keySerializer] if it
     * does not already exist. Subsequent calls with the same [name] return the existing cache.
     */
    suspend fun <K> maybeAddCache(
        name: String,
        keySerializer: CacheKeySerializer<K>,
        expiration: Duration = 10.minutes
    ): Cache<K>

    /**
     * Returns a previously registered cache by [name].
     *
     * @throws IllegalStateException if no cache with that name has been registered
     */
    suspend fun <K> getCache(name: String): Cache<K>

    /** Evicts expired entries from every managed cache. */
    suspend fun evictExpiredItems()

    /** Clears all entries from every managed cache. */
    suspend fun clearAll()
}

val CacheDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

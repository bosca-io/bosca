package bosca.ratelimit

import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.cache.StringCacheKey
import bosca.cache.serializers.StringKeySerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Distributed, per-key rate limiter backed by the framework's [CacheManager] (Redis or NATS), so the count is
 * shared across all server instances rather than living in one process. Tracks attempts per key within a
 * sliding [window]; a key is limited once it reaches [maxAttempts].
 *
 * @param cacheManager the distributed cache manager for cross-instance state
 * @param maxAttempts attempts per key before the key is limited
 * @param window the time window over which attempts are counted
 * @param cacheName selects the shared cache AND its TTL — [CacheManager.maybeAddCache] ignores the window of
 *   every caller after the first for a given name, so a limiter that needs a distinct window MUST use a
 *   distinct name; reusing one silently inherits whichever window created the cache first.
 */
open class RateLimiter(
    private val cacheManager: CacheManager,
    private val maxAttempts: Int = 10,
    private val window: Duration = 1.minutes,
    private val cacheName: String = DEFAULT_CACHE_NAME,
) {
    private var cache: Cache<String>? = null

    private suspend fun cache(): Cache<String> =
        cache ?: cacheManager.maybeAddCache(cacheName, StringKeySerializer, window).also { cache = it }

    /** Returns true once [key] has reached [maxAttempts] within the current window. */
    suspend fun isRateLimited(key: String): Boolean {
        val cacheKey = StringCacheKey(cacheName, key.lowercase())
        val value = cache().get(cacheKey)
        if (!value.exists) return false
        val count = value.value?.toIntOrNull() ?: return false
        return count >= maxAttempts
    }

    /**
     * Records one attempt against [key]. Uses an optimistic read-then-write with the cache's TTL to increment
     * the counter. Under concurrent requests the count may under-increment slightly, which is acceptable — the
     * limiter is a best-effort defense, not an exact counter. Re-putting the same key resets the TTL, so the
     * window extends from the most recent attempt rather than the first.
     */
    suspend fun recordFailure(key: String) {
        val cacheKey = StringCacheKey(cacheName, key.lowercase())
        val cache = cache()
        val current = cache.get(cacheKey)
        val count = if (current.exists) (current.value?.toIntOrNull() ?: 0) + 1 else 1
        cache.put(cacheKey, count.toString())
    }

    /**
     * Decrements the count for [key] after a success. Decrements rather than clears so one legitimate success
     * doesn't fully reset the counter during an ongoing attack against the same key from other sources.
     */
    suspend fun recordSuccess(key: String) {
        val cacheKey = StringCacheKey(cacheName, key.lowercase())
        val cache = cache()
        val current = cache.get(cacheKey)
        val count = if (current.exists) (current.value?.toIntOrNull() ?: 0) - 1 else 0
        if (count <= 0) cache.remove(cacheKey) else cache.put(cacheKey, count.toString())
    }

    companion object {
        private const val DEFAULT_CACHE_NAME = "rate-limit"
    }
}

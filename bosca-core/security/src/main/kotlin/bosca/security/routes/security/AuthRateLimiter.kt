package bosca.security.routes.security

import bosca.cache.CacheManager
import bosca.ratelimit.RateLimiter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Per-identifier rate limiter for authentication endpoints (login, signup, password reset) to blunt
 * credential brute-force and resend abuse. A thin specialization of the generic [RateLimiter] that defaults
 * to the shared `auth:rate-limit` cache; all behavior (distributed counting, TTL-based windows, decrement on
 * success) is inherited unchanged.
 *
 * @param cacheManager the distributed cache manager for cross-instance state
 * @param maxAttempts maximum attempts per identifier before lockout
 * @param window the time window for counting attempts
 * @param cacheName the shared cache (and its TTL); a limiter needing a distinct window MUST use a distinct name
 */
class AuthRateLimiter(
    cacheManager: CacheManager,
    maxAttempts: Int = 10,
    window: Duration = 1.minutes,
    cacheName: String = CACHE_NAME,
) : RateLimiter(cacheManager, maxAttempts, window, cacheName) {

    companion object {
        private const val CACHE_NAME = "auth:rate-limit"
    }
}

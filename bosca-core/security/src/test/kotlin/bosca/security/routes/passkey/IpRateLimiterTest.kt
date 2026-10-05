package bosca.security.routes.passkey

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IpRateLimiterTest {

    @Test
    fun `limits each IP independently within its window`() {
        val limiter = IpRateLimiter(maxRequests = 2, windowSeconds = 60)

        assertFalse(limiter.isRateLimited("192.0.2.1"))
        assertFalse(limiter.isRateLimited("192.0.2.1"))
        assertTrue(limiter.isRateLimited("192.0.2.1"))
        assertFalse(limiter.isRateLimited("192.0.2.2"))
    }

    @Test
    fun `expired requests no longer consume capacity`() {
        val limiter = IpRateLimiter(maxRequests = 1, windowSeconds = 0)

        assertFalse(limiter.isRateLimited("192.0.2.1"))
        assertFalse(limiter.isRateLimited("192.0.2.1"))
    }

    @Test
    fun `large address sets exercise bounded stale entry maintenance`() {
        val limiter = IpRateLimiter(maxRequests = 2, windowSeconds = 60)
        repeat(1_002) {
            assertFalse(limiter.isRateLimited("198.51.100.$it"))
        }

        assertFalse(limiter.isRateLimited("203.0.113.1"))
    }

    @Test
    fun `bounded maintenance removes stale entries and zero capacity never records entries`() {
        val staleLimiter = IpRateLimiter(maxRequests = 2, windowSeconds = 0)
        repeat(1_002) {
            assertFalse(staleLimiter.isRateLimited("198.51.100.$it"))
        }
        assertFalse(staleLimiter.isRateLimited("203.0.113.1"))

        val zeroCapacity = IpRateLimiter(maxRequests = 0, windowSeconds = 60)
        assertTrue(zeroCapacity.isRateLimited("192.0.2.1"))
        assertTrue(zeroCapacity.isRateLimited("192.0.2.1"))
    }
}

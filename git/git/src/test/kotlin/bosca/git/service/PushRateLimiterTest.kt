package bosca.git.service

import bosca.counter.Counter
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushRateLimiterTest {

    /** In-memory stand-in for the distributed [Counter], matching its atomic-increment semantics. */
    private class FakeCounter : Counter {
        val values = ConcurrentHashMap<String, Long>()
        override suspend fun increment(key: String, by: Long): Long =
            values.compute(key) { _, current -> (current ?: 0L) + by } ?: by
        override suspend fun get(key: String): Long = values[key] ?: 0L
        override suspend fun get(keys: List<String>): Map<String, Long> = keys.associateWith { values[it] ?: 0L }
    }

    /** [PushRateLimiterImpl] with a controllable clock so window rollover is testable. */
    private class TestablePushRateLimiter(
        counter: Counter,
        application: BoscaApplication,
        private val nowSeconds: () -> Long,
    ) : PushRateLimiterImpl(counter, application) {
        override fun nowEpochSeconds(): Long = nowSeconds()
    }

    private fun app(yaml: String = ""): BoscaApplication {
        val application = mockk<BoscaApplication>(relaxed = true)
        every { application.config } returns ApplicationConfig.load(yaml.byteInputStream())
        return application
    }

    private fun limiter(
        yaml: String = "",
        counter: Counter = FakeCounter(),
        now: () -> Long = { 0L },
    ) = TestablePushRateLimiter(counter, app(yaml), now)

    @Test
    fun `allows pushes within the configured limit`() = runTest {
        val limiter = limiter(RATE_LIMIT_YAML.replace("MAX", "5"))
        val profileId = UUID.random()
        for (i in 1..5) {
            assertTrue(limiter.tryAcquire(profileId), "Push $i should be allowed")
        }
    }

    @Test
    fun `rejects pushes exceeding the configured limit`() = runTest {
        val limiter = limiter(RATE_LIMIT_YAML.replace("MAX", "3"))
        val profileId = UUID.random()
        repeat(3) { assertTrue(limiter.tryAcquire(profileId)) }
        assertFalse(limiter.tryAcquire(profileId), "4th push should be rejected")
    }

    @Test
    fun `different profiles have independent limits`() = runTest {
        val limiter = limiter(RATE_LIMIT_YAML.replace("MAX", "2"))
        val profile1 = UUID.random()
        val profile2 = UUID.random()
        repeat(3) { limiter.tryAcquire(profile1) }
        assertTrue(limiter.tryAcquire(profile2), "A different profile has its own bucket")
    }

    @Test
    fun `disabling via config bypasses limiting and never touches the counter`() = runTest {
        val counter = FakeCounter()
        val yaml = """
            git:
              push:
                rate-limit:
                  enabled: false
                  max: 1
        """.trimIndent()
        val limiter = limiter(yaml, counter)
        val profileId = UUID.random()
        repeat(100) { assertTrue(limiter.tryAcquire(profileId), "Every push is allowed when disabled") }
        assertTrue(counter.values.isEmpty(), "A disabled limiter must not increment the counter")
    }

    @Test
    fun `a new window resets the limit`() = runTest {
        var now = 0L
        val limiter = limiter(
            yaml = """
                git:
                  push:
                    rate-limit:
                      max: 2
                      window-seconds: 60
            """.trimIndent(),
            now = { now },
        )
        val profileId = UUID.random()
        assertTrue(limiter.tryAcquire(profileId))
        assertTrue(limiter.tryAcquire(profileId))
        assertFalse(limiter.tryAcquire(profileId), "3rd push in the window is rejected")

        now = 60 // advance into the next fixed window
        assertTrue(limiter.tryAcquire(profileId), "The next window starts with a fresh count")
    }

    @Test
    fun `defaults apply when no configuration is present`() = runTest {
        assertEquals(10_000L, PushRateLimiterImpl.DEFAULT_MAX)
        assertEquals(3_600L, PushRateLimiterImpl.DEFAULT_WINDOW_SECONDS)
        val limiter = limiter() // empty config
        val profileId = UUID.random()
        for (i in 1..PushRateLimiterImpl.DEFAULT_MAX) {
            assertTrue(limiter.tryAcquire(profileId), "Push $i is within the default limit")
        }
        assertFalse(limiter.tryAcquire(profileId), "Push past the default limit is rejected")
    }

    @Test
    fun `uses the real system clock when not overridden`() = runTest {
        // Construct the production impl directly (no clock override) so the default
        // System-clock window path is exercised. Two pushes land in the same window.
        val realLimiter = PushRateLimiterImpl(FakeCounter(), app(RATE_LIMIT_YAML.replace("MAX", "1")))
        val profileId = UUID.random()
        assertTrue(realLimiter.tryAcquire(profileId))
        assertFalse(realLimiter.tryAcquire(profileId), "Second push in the same window is rejected")
    }

    @Test
    fun `malformed configuration falls back to defaults`() = runTest {
        val yaml = """
            git:
              push:
                rate-limit:
                  enabled: notabool
                  max: notanumber
                  window-seconds: 0
        """.trimIndent()
        val limiter = limiter(yaml)
        val profileId = UUID.random()
        // enabled falls back to true (still limiting), max falls back to the default,
        // and window-seconds 0 is coerced to 1 rather than dividing by zero.
        assertTrue(limiter.tryAcquire(profileId))
    }

    companion object {
        private val RATE_LIMIT_YAML = """
            git:
              push:
                rate-limit:
                  max: MAX
        """.trimIndent()
    }
}

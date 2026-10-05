package bosca.cli.ci

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryTest {

    @Test
    fun `succeeds on first attempt`() = runTest {
        var attempts = 0
        val result = withRetry {
            attempts++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `retries on failure and succeeds eventually`() = runTest {
        var attempts = 0
        val result = withRetry(maxAttempts = 3, initialDelayMs = 1) {
            attempts++
            if (attempts < 3) throw RuntimeException("fail #$attempts")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `throws after exhausting all attempts`() = runTest {
        var attempts = 0
        assertFailsWith<RuntimeException> {
            withRetry(maxAttempts = 3, initialDelayMs = 1) {
                attempts++
                throw RuntimeException("permanent failure")
            }
        }
        assertEquals(3, attempts)
    }

    @Test
    fun `retries exactly maxAttempts times`() = runTest {
        var attempts = 0
        assertFailsWith<RuntimeException> {
            withRetry(maxAttempts = 5, initialDelayMs = 1) {
                attempts++
                throw RuntimeException("fail")
            }
        }
        assertEquals(5, attempts)
    }

    @Test
    fun `single attempt does not retry`() = runTest {
        var attempts = 0
        assertFailsWith<RuntimeException> {
            withRetry(maxAttempts = 1, initialDelayMs = 1) {
                attempts++
                throw RuntimeException("fail")
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `preserves the last exception message`() = runTest {
        val ex = assertFailsWith<RuntimeException> {
            withRetry(maxAttempts = 2, initialDelayMs = 1) {
                throw RuntimeException("attempt failed")
            }
        }
        assertEquals("attempt failed", ex.message)
    }
}

package bosca.db

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionLimiterTest {

    @Test
    fun `ConnectionLimiter acquire and release works`() = runTest {
        val limiter = ConnectionLimiter(limit = 2)
        limiter.acquire()
        limiter.acquire()
        limiter.release()
        limiter.release()
    }

    @Test
    fun `ConnectionLimiter release after acquire allows reacquire`() = runTest {
        val limiter = ConnectionLimiter(limit = 1)
        limiter.acquire()
        limiter.release()
        limiter.acquire()
        limiter.release()
    }

    @Test
    fun `ConnectionLimiter asCoroutineContext returns context element`() {
        val limiter = ConnectionLimiter(limit = 3)
        val context = limiter.asCoroutineContext()
        assertNotNull(context)
    }

    @Test
    fun `connectionLimiter retrieves from coroutine context`() = runTest {
        val limiter = ConnectionLimiter(limit = 2)
        withContext(limiter.asCoroutineContext()) {
            assertSame(limiter, connectionLimiter())
            assertSame(limiter, connectionLimiterOrNull())
        }
    }

    @Test
    fun `connection limiter helpers report a missing context`() = runTest {
        assertNull(connectionLimiterOrNull())
        assertFailsWith<IllegalStateException> { connectionLimiter() }
    }

    @Test
    fun `acquire suspends at the limit and resumes after release`() = runTest {
        val limiter = ConnectionLimiter(limit = 1)
        limiter.acquire()
        var acquired = false
        val waiting = async {
            limiter.acquire()
            acquired = true
        }

        yield()
        assertFalse(acquired)

        limiter.release()
        waiting.await()
        assertTrue(acquired)
        limiter.release()
    }
}

package bosca.sharedqueue.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit coverage for the three job-control exception types. They carry the
 * runner's failure/retry semantics, so their message/time payloads must be
 * preserved exactly as constructed.
 */
class JobExceptionsTest {

    @Test
    fun `FailException preserves its message`() {
        val ex: Exception = FailException("permanent boom")
        assertEquals("permanent boom", ex.message)
    }

    @Test
    fun `DelayException carries the retry delay and has no message`() {
        val ex = DelayException(5.seconds)
        assertEquals(5.seconds, ex.time)
        assertNull(ex.message)
    }

    @Test
    fun `DelayException is open for subclassing`() {
        // DelayException is declared `open` so specialized delays (e.g. a
        // backoff variant) can subclass it and still be caught by the runner's
        // `catch (e: DelayException)` arm.
        val ex = object : DelayException(10.seconds) {}
        assertEquals(10.seconds, ex.time)
        assertTrue(ex is DelayException)
    }

    @Test
    fun `LockAcquisitionException preserves its message`() {
        val ex: Exception = LockAcquisitionException("could not lock job")
        assertEquals("could not lock job", ex.message)
    }
}

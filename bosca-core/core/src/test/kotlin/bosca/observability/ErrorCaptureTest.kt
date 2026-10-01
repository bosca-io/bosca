package bosca.observability

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Contract tests for [ErrorCapture] and its [ErrorCapture.Noop]
 * implementation. [Noop] is wired into 30+ catch blocks across
 * security, analytics, experimentation, and content modules —
 * its contract is that it must never throw and must log at warn
 * level. A broken [Noop] would cascade into every swallowed-
 * exception path in the system.
 */
class ErrorCaptureTest {

    @Test
    fun `Noop implements ErrorCapture`() {
        assertIs<ErrorCapture>(ErrorCapture.Noop)
    }

    @Test
    fun `Noop capture does not throw on RuntimeException`() = runTest {
        ErrorCapture.Noop.capture(
            RuntimeException("test error"),
            call = null,
            context = mapOf("key" to "value"),
        )
    }

    @Test
    fun `Noop capture does not throw with empty context`() = runTest {
        ErrorCapture.Noop.capture(
            IllegalStateException("something broke"),
            call = null,
            context = emptyMap(),
        )
    }

    @Test
    fun `Noop capture does not throw on nested exception`() = runTest {
        val cause = IllegalArgumentException("root cause")
        val wrapper = RuntimeException("wrapper", cause)
        ErrorCapture.Noop.capture(wrapper, call = null, context = mapOf("op" to "test"))
    }

    @Test
    fun `ErrorCapture is a fun interface and can be created as a lambda`() = runTest {
        var captured: Throwable? = null
        val tracker = ErrorCapture { throwable, _, _ -> captured = throwable }
        val error = RuntimeException("tracked")
        tracker.capture(error, call = null, context = emptyMap())
        kotlin.test.assertEquals(error, captured)
    }
}

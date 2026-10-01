package bosca.telemetry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TracingTest {

    @Test
    fun `new trace IDs are opaque 128-bit hex values`() {
        val first = Tracing.newTraceId()
        val second = Tracing.newTraceId()

        assertTrue(first.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(first, second)
    }

    @Test
    fun `trace ID follows coroutine across dispatcher changes and is restored afterward`() = runTest {
        val traceId = Tracing.newTraceId()
        assertNull(Tracing.currentTraceId())

        Tracing.withTrace(traceId) {
            assertEquals(traceId, Tracing.currentTraceId())
            withContext(Dispatchers.Default) {
                assertEquals(traceId, Tracing.currentTraceId())
            }
        }

        assertNull(Tracing.currentTraceId())
    }

    @Test
    fun `nested trace scope inherits the current trace by default`() = runTest {
        val traceId = Tracing.newTraceId()

        Tracing.withTrace(traceId) {
            Tracing.withTrace {
                assertEquals(traceId, Tracing.currentTraceId())
            }
        }
    }
}

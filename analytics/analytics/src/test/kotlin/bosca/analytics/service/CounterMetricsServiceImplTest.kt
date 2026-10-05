package bosca.analytics.service

import bosca.analytics.model.AnalyticsCounterType
import bosca.counter.Counter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CounterMetricsServiceImpl]: resolving counter families, type-driven window aggregation (GAUGE max /
 * COUNTER sum), the dense zero-filled series, unknown-counter handling, and window clamping.
 *
 * The clock is pinned by subclassing so bucket indices are deterministic.
 */
class CounterMetricsServiceImplTest {

    private val counter = mockk<Counter>()

    /** Fixes [nowEpochSeconds] so bucket math is deterministic. */
    private fun service(nowSeconds: Long) = object : CounterMetricsServiceImpl(counter) {
        override fun nowEpochSeconds(): Long = nowSeconds
    }

    // ---- counter(id): family resolution --------------------------------------------------------

    @Test
    fun `counter resolves the sessions family to a GAUGE`() = runTest {
        val result = service(nowSeconds = 9_000).counter("sessions.mobile")
        assertEquals("sessions.mobile", result?.id)
        assertEquals(AnalyticsCounterType.GAUGE, result?.type)
    }

    @Test
    fun `counter resolves the http family to a COUNTER`() = runTest {
        val result = service(nowSeconds = 6_000).counter("http.bosca.5xx")
        assertEquals(AnalyticsCounterType.COUNTER, result?.type)
    }

    @Test
    fun `counter returns null for an unknown family`() = runTest {
        assertNull(service(nowSeconds = 0).counter("widgets.foo"))
    }

    // ---- value(): aggregation ------------------------------------------------------------------

    @Test
    fun `value sums the window for a COUNTER family`() = runTest {
        // now = 6000s -> current 1-min bucket = 100; window 3 -> buckets 98..100.
        coEvery {
            counter.get(listOf("http.bosca.5xx.98", "http.bosca.5xx.99", "http.bosca.5xx.100"))
        } returns mapOf("http.bosca.5xx.98" to 2L, "http.bosca.5xx.99" to 3L, "http.bosca.5xx.100" to 5L)

        assertEquals(10L, service(nowSeconds = 6_000).value("http.bosca.5xx", window = 3))
    }

    @Test
    fun `value takes the peak of the window for a GAUGE family`() = runTest {
        // now = 9000s -> current 15-min bucket = 10; default window 2 -> buckets 9..10.
        coEvery {
            counter.get(listOf("sessions.mobile.9", "sessions.mobile.10"))
        } returns mapOf("sessions.mobile.9" to 7L, "sessions.mobile.10" to 4L)

        // window omitted -> family default (2).
        assertEquals(7L, service(nowSeconds = 9_000).value("sessions.mobile", window = null))
    }

    @Test
    fun `value is zero for an empty window`() = runTest {
        coEvery { counter.get(any<List<String>>()) } returns emptyMap<String, Long>()
        assertEquals(0L, service(nowSeconds = 6_000).value("http.bosca.5xx", window = 3))
    }

    @Test
    fun `value is zero for an unknown family and never touches the counter`() = runTest {
        assertEquals(0L, service(nowSeconds = 6_000).value("widgets.foo", window = null))
        coVerify(exactly = 0) { counter.get(any<List<String>>()) }
    }

    // ---- values(): dense, zero-filled, ordered series ------------------------------------------

    @Test
    fun `values returns a dense zero-filled series ordered oldest to newest`() = runTest {
        // now = 6000s -> bucket 100; window 3 -> buckets 98,99,100. Bucket 99 is absent -> zero-filled.
        coEvery {
            counter.get(listOf("http.bosca.2xx.98", "http.bosca.2xx.99", "http.bosca.2xx.100"))
        } returns mapOf("http.bosca.2xx.98" to 1L, "http.bosca.2xx.100" to 4L)

        val series = service(nowSeconds = 6_000).values("http.bosca.2xx", window = 3)

        assertEquals(listOf(1L, 0L, 4L), series.map { it.value })
        // Bucket start = index * 60s, UTC.
        assertEquals(
            listOf(98L * 60, 99L * 60, 100L * 60).map {
                OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(it), ZoneOffset.UTC)
            },
            series.map { it.time },
        )
    }

    @Test
    fun `values is empty for an unknown family`() = runTest {
        assertTrue(service(nowSeconds = 6_000).values("widgets.foo", window = null).isEmpty())
    }

    // ---- window clamping -----------------------------------------------------------------------

    @Test
    fun `value clamps a zero window to a single bucket`() = runTest {
        val keys = slot<List<String>>()
        coEvery { counter.get(capture(keys)) } returns emptyMap()

        service(nowSeconds = 6_000).value("http.bosca.5xx", window = 0)

        assertEquals(listOf("http.bosca.5xx.100"), keys.captured)
    }

    @Test
    fun `values clamps an oversized window to the maximum`() = runTest {
        val keys = slot<List<String>>()
        coEvery { counter.get(capture(keys)) } returns emptyMap()

        service(nowSeconds = 6_000).values("http.bosca.5xx", window = 99_999)

        assertEquals(CounterMetricsServiceImpl.MAX_WINDOW, keys.captured.size)
    }
}

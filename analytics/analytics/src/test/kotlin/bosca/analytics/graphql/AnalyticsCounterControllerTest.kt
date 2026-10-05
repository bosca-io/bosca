package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsCounter
import bosca.analytics.model.AnalyticsCounterType
import bosca.analytics.model.AnalyticsCounterValue
import bosca.analytics.service.CounterMetricsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsCounterControllerTest {

    private val service = mockk<CounterMetricsService>()
    private val controller = AnalyticsCounterController(service)

    private val counter = AnalyticsCounter("http.bosca.5xx", AnalyticsCounterType.COUNTER)

    @Test
    fun `id and type project the carried counter`() {
        assertEquals("http.bosca.5xx", controller.id(counter))
        assertEquals(AnalyticsCounterType.COUNTER, controller.type(counter))
    }

    @Test
    fun `value delegates to the service with the requested window`() = runTest {
        coEvery { service.value("http.bosca.5xx", 5) } returns 42L

        assertEquals(42L, controller.value(counter, window = 5))
        coVerify { service.value("http.bosca.5xx", 5) }
    }

    @Test
    fun `value passes a null window through for the family default`() = runTest {
        coEvery { service.value("http.bosca.5xx", null) } returns 7L

        assertEquals(7L, controller.value(counter))
        coVerify { service.value("http.bosca.5xx", null) }
    }

    @Test
    fun `values delegates to the service and returns the series`() = runTest {
        val series = listOf(
            AnalyticsCounterValue(OffsetDateTime.ofInstant(Instant.ofEpochSecond(60), ZoneOffset.UTC), 1L),
            AnalyticsCounterValue(OffsetDateTime.ofInstant(Instant.ofEpochSecond(120), ZoneOffset.UTC), 2L),
        )
        coEvery { service.values("http.bosca.5xx", 2) } returns series

        assertEquals(series, controller.values(counter, window = 2))
    }
}

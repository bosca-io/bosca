package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsCounterValue
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsCounterValueControllerTest {

    private val controller = AnalyticsCounterValueController()

    @Test
    fun `time and value project the sample`() {
        val time = OffsetDateTime.ofInstant(Instant.ofEpochSecond(900), ZoneOffset.UTC)
        val sample = AnalyticsCounterValue(time, 3L)

        assertEquals(time, controller.time(sample))
        assertEquals(3L, controller.value(sample))
    }
}

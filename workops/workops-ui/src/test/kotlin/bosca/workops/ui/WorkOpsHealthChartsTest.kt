package bosca.workops.ui

import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.*

class WorkOpsHealthChartsTest {
    @Test fun `HTTP rates join actual instants despite different array order offsets and missing buckets`() {
        val success = WorkOpsHealthCounter(100, listOf(WorkOpsHealthSample("2027-01-01T00:02:00Z", 90), WorkOpsHealthSample("2027-01-01T00:01:00Z", 10)))
        val client = WorkOpsHealthCounter(10, listOf(WorkOpsHealthSample("2027-01-01T01:01:00+01:00", 5), WorkOpsHealthSample("2027-01-01T00:02:00Z", 5)))
        val server = WorkOpsHealthCounter(10, listOf(WorkOpsHealthSample("2027-01-01T00:02:00Z", 5)))
        val points = workOpsHttpRateSamples(success, client, server)
        assertEquals(1, points.size); assertEquals(Instant.parse("2027-01-01T00:02:00Z"), points.single().first); assertEquals(10.0, points.single().second)
        assertTrue(workOpsHttpRateSamples(success, client, null).isEmpty())
    }

    @Test fun `charts retain real empty and single point histories without a fabricated zero baseline`() {
        val empty = workOpsHealthChart("Sessions", emptyList()).option.getValue("series").jsonArray.single().jsonObject.getValue("data").jsonArray
        assertTrue(empty.isEmpty())
        val time = Instant.parse("2027-01-01T00:00:00Z")
        val single = workOpsHealthChart("Sessions", listOf(time to 7L)).option.getValue("series").jsonArray.single().jsonObject.getValue("data").jsonArray
        assertEquals(1, single.size); assertEquals("2027-01-01T00:00:00Z", single.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(7L, single.single().jsonObject.getValue("value").jsonArray[1].jsonPrimitive.long)
    }

    @Test fun `reported zero response buckets yield zero rate while absent buckets stay absent`() {
        val zero = WorkOpsHealthCounter(0, listOf(WorkOpsHealthSample("2027-01-01T00:00:00Z", 0)))
        assertEquals(0.0, workOpsHttpRateSamples(zero, zero, zero).single().second)
        assertTrue(workOpsHttpRateSamples(zero, zero, zero.copy(samples = emptyList())).isEmpty())
    }
}

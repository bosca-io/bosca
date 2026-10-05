package bosca.nats.admin.model

import kotlin.test.Test
import kotlin.test.assertEquals

class NatsSubscriptionsInfoTest {

    @Test
    fun `NatsSubscriptionsInfo stores all fields`() {
        val info = NatsSubscriptionsInfo(
            numSubscriptions = 100,
            numCache = 50,
            numInserts = 200,
            numRemoves = 30,
            numMatches = 500,
            cacheHitRate = 0.85,
            maxFanout = 10,
            avgFanout = 3.5
        )
        assertEquals(100, info.numSubscriptions)
        assertEquals(50, info.numCache)
        assertEquals(200, info.numInserts)
        assertEquals(30, info.numRemoves)
        assertEquals(500, info.numMatches)
        assertEquals(0.85, info.cacheHitRate)
        assertEquals(10, info.maxFanout)
        assertEquals(3.5, info.avgFanout)
    }

    @Test
    fun `NatsSubscriptionsInfo equality`() {
        val a = NatsSubscriptionsInfo(
            numSubscriptions = 1, numCache = 1, numInserts = 1,
            numRemoves = 1, numMatches = 1, cacheHitRate = 0.5,
            maxFanout = 1, avgFanout = 1.0
        )
        val b = a.copy()
        assertEquals(a, b)
    }

    @Test
    fun `NatsSubscriptionsInfo copy with modification`() {
        val info = NatsSubscriptionsInfo(
            numSubscriptions = 10, numCache = 5, numInserts = 20,
            numRemoves = 2, numMatches = 50, cacheHitRate = 0.9,
            maxFanout = 8, avgFanout = 2.0
        )
        val updated = info.copy(numSubscriptions = 20, cacheHitRate = 0.95)
        assertEquals(20, updated.numSubscriptions)
        assertEquals(0.95, updated.cacheHitRate)
        assertEquals(5, updated.numCache)
    }
}

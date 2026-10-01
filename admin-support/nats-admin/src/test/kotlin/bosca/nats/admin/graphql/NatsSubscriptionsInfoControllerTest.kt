package bosca.nats.admin.graphql

import bosca.nats.admin.model.NatsSubscriptionsInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class NatsSubscriptionsInfoControllerTest {

    private val controller = NatsSubscriptionsInfoController()

    private val info = NatsSubscriptionsInfo(
        numSubscriptions = 150,
        numCache = 64,
        numInserts = 200,
        numRemoves = 50,
        numMatches = 1000,
        cacheHitRate = 0.95,
        maxFanout = 10,
        avgFanout = 3.5
    )

    @Test
    fun `numSubscriptions delegates to model field`() {
        assertEquals(150, controller.numSubscriptions(info))
    }

    @Test
    fun `numCache delegates to model field`() {
        assertEquals(64, controller.numCache(info))
    }

    @Test
    fun `numInserts delegates to model field`() {
        assertEquals(200, controller.numInserts(info))
    }

    @Test
    fun `numRemoves delegates to model field`() {
        assertEquals(50, controller.numRemoves(info))
    }

    @Test
    fun `numMatches delegates to model field`() {
        assertEquals(1000, controller.numMatches(info))
    }

    @Test
    fun `cacheHitRate delegates to model field`() {
        assertEquals(0.95, controller.cacheHitRate(info))
    }

    @Test
    fun `maxFanout delegates to model field`() {
        assertEquals(10, controller.maxFanout(info))
    }

    @Test
    fun `avgFanout delegates to model field`() {
        assertEquals(3.5, controller.avgFanout(info))
    }
}

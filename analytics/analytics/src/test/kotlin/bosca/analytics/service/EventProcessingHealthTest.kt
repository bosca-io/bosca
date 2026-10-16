package bosca.analytics.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class EventProcessingHealthTest {
    @Test
    fun `processing failure immediately removes readiness and expires after sixty seconds`() {
        var now = 0L
        val health = EventProcessingHealth { now }
        assertTrue(health.isReady)
        assertTrue(health.isLive)

        health.processingFailed()
        assertFalse(health.isReady)
        now = 59.seconds.inWholeNanoseconds
        health.processingFailed()
        assertTrue(health.isLive)
        now = 60.seconds.inWholeNanoseconds
        assertFalse(health.isLive)

        health.processingRecovered()
        assertTrue(health.isReady)
        assertTrue(health.isLive)
        health.processingFailed()
        assertTrue(health.isLive)
    }

    @Test
    fun `NATS failures survive successful processing and retain their own grace period`() {
        var now = 0L
        val health = EventProcessingHealth { now }
        health.natsFailed()
        health.processingRecovered()
        assertFalse(health.isReady)
        now = 59.seconds.inWholeNanoseconds
        health.natsFailed()
        assertTrue(health.isLive)
        now = 60.seconds.inWholeNanoseconds
        assertFalse(health.isLive)

        health.processingFailed()
        health.natsRecovered()
        assertFalse(health.isReady)
        assertTrue(health.isLive)
        health.processingRecovered()
        assertTrue(health.isReady)
        assertTrue(health.isLive)
    }
}

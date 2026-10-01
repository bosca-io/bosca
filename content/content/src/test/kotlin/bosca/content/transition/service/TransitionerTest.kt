package bosca.content.transition.service

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals

class TransitionerTest {

    @Test
    fun hasCompanionObject() {
        assertNotNull(Transitioner.Companion)
    }

    @Test
    fun getDelayReturnNullForPastTimestamp() {
        with(Transitioner.Companion) {
            val pastTimestamp = System.currentTimeMillis() - 100_000L
            val result = pastTimestamp.getDelay(allowPast = false)
            assertNull(result)
        }
    }

    @Test
    fun getDelayReturnsValueForPastTimestampWhenAllowed() {
        with(Transitioner.Companion) {
            val pastTimestamp = System.currentTimeMillis() - 100L
            val result = pastTimestamp.getDelay(allowPast = true)
            assertNotNull(result)
        }
    }

    @Test
    fun getDelayReturnsValueForFutureTimestamp() {
        with(Transitioner.Companion) {
            val futureTimestamp = System.currentTimeMillis() + 100_000L
            val result = futureTimestamp.getDelay(allowPast = false)
            assertNotNull(result)
        }
    }

    @Test
    fun getEffectiveAdvertisedEpochReturnsNullWhenNull() {
        val result = Transitioner.getEffectiveAdvertisedEpoch(null, null)
        assertNull(result)
    }

    @Test
    fun getEffectiveAdvertisedEpochReturnsNullWhenZero() {
        val result = Transitioner.getEffectiveAdvertisedEpoch(0L, null)
        assertNull(result)
    }

    @Test
    fun getEffectiveAdvertisedEpochReturnsNullWhenAfterPublished() {
        val result = Transitioner.getEffectiveAdvertisedEpoch(200L, 100L)
        assertNull(result)
    }

    @Test
    fun getEffectiveAdvertisedEpochReturnsValueWhenBeforePublished() {
        val result = Transitioner.getEffectiveAdvertisedEpoch(100L, 200L)
        assertEquals(100L, result)
    }

    @Test
    fun getEffectiveAdvertisedEpochReturnsValueWhenNoPublished() {
        val result = Transitioner.getEffectiveAdvertisedEpoch(100L, null)
        assertEquals(100L, result)
    }
}

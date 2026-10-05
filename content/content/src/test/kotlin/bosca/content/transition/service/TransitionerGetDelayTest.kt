package bosca.content.transition.service

import bosca.content.transition.service.Transitioner.Companion.getDelay
import bosca.content.transition.service.Transitioner.Companion.getEffectiveAdvertisedEpoch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class TransitionerGetDelayTest {

    @Test
    fun `getDelay returns null for epoch time in the past`() {
        val pastEpochMillis = Clock.System.now().toEpochMilliseconds() - 60_000L
        assertNull(pastEpochMillis.getDelay())
    }

    @Test
    fun `getDelay returns non-null for epoch time in the future`() {
        val futureEpochMillis = Clock.System.now().toEpochMilliseconds() + 60_000L
        assertNotNull(futureEpochMillis.getDelay())
    }

    @Test
    fun `getDelay returns null for epoch time far in the past`() {
        val farPastEpochMillis = 0L
        assertNull(farPastEpochMillis.getDelay())
    }

    @Test
    fun `getDelay returns non-null for epoch time far in the future`() {
        val farFutureEpochMillis = Clock.System.now().toEpochMilliseconds() + 365L * 24 * 60 * 60 * 1000
        assertNotNull(farFutureEpochMillis.getDelay())
    }

    // --- getEffectiveAdvertisedEpoch tests ---

    @Test
    fun `effective advertised is null when advertised is null`() {
        assertNull(getEffectiveAdvertisedEpoch(null, 2000L))
    }

    @Test
    fun `effective advertised is null when advertised is zero`() {
        assertNull(getEffectiveAdvertisedEpoch(0L, 2000L))
    }

    @Test
    fun `effective advertised is null when advertised is negative`() {
        assertNull(getEffectiveAdvertisedEpoch(-1L, 2000L))
    }

    @Test
    fun `effective advertised is null when advertised equals published`() {
        assertNull(getEffectiveAdvertisedEpoch(1000L, 1000L))
    }

    @Test
    fun `effective advertised is null when advertised is after published`() {
        assertNull(getEffectiveAdvertisedEpoch(2000L, 1000L))
    }

    @Test
    fun `effective advertised is returned when advertised is before published`() {
        assertEquals(1000L, getEffectiveAdvertisedEpoch(1000L, 2000L))
    }

    @Test
    fun `effective advertised is returned when published is null`() {
        assertEquals(1000L, getEffectiveAdvertisedEpoch(1000L, null))
    }

    @Test
    fun `effective advertised is null when both are null`() {
        assertNull(getEffectiveAdvertisedEpoch(null, null))
    }

    @Test
    fun `effective advertised is null when advertised is zero and published is null`() {
        assertNull(getEffectiveAdvertisedEpoch(0L, null))
    }
}

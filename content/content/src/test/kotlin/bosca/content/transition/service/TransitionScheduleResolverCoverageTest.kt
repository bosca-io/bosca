package bosca.content.transition.service

import bosca.content.transition.service.TransitionScheduleResolver.toDelay
import bosca.serialization.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Supplemental coverage for [TransitionScheduleResolver] that exercises the
 * `Long.toDelay()` extension. Unlike `toFutureDelay()` (covered in
 * [TransitionScheduleResolverTest]), `toDelay()` always returns a non-null
 * [OffsetDateTime] regardless of whether the epoch is in the future or the
 * past, so it needs its own dedicated cases for both the positive-diff and
 * negative-diff arithmetic paths.
 */
class TransitionScheduleResolverCoverageTest {

    @Test
    fun `toDelay returns a future timestamp for a future epoch`() {
        val futureEpochMillis = System.currentTimeMillis() + 60_000L

        val result = futureEpochMillis.toDelay()

        assertNotNull(result)
        assertTrue(
            result > OffsetDateTime.now(),
            "A future epoch should resolve to a timestamp in the future",
        )
    }

    @Test
    fun `toDelay returns a past timestamp for a past epoch`() {
        val pastEpochMillis = System.currentTimeMillis() - 60_000L

        val result = pastEpochMillis.toDelay()

        // Unlike toFutureDelay(), toDelay() never returns null; a past epoch
        // yields a negative diff and therefore a timestamp in the past.
        assertTrue(
            result < OffsetDateTime.now(),
            "A past epoch should resolve to a timestamp in the past",
        )
    }

    @Test
    fun `toDelay for epoch zero resolves to well before now`() {
        val result = 0L.toDelay()

        assertNotNull(result)
        assertTrue(
            result < OffsetDateTime.now(),
            "Epoch zero is far in the past and must resolve to a past timestamp",
        )
    }

    @Test
    fun `toDelay for near-now epoch is close to now`() {
        val nowEpochMillis = System.currentTimeMillis()

        val result = nowEpochMillis.toDelay()

        assertNotNull(result)
        val skewMillis = java.time.Duration
            .between(result.toInstant(), OffsetDateTime.now().toInstant())
            .abs()
            .toMillis()
        assertTrue(
            skewMillis < 5_000,
            "A near-now epoch should resolve to approximately now, but was off by ${skewMillis}ms",
        )
    }
}

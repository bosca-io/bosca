package bosca.analytics.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RageClickTrackerTest {
    @Test
    fun `reports a burst when the threshold occurs inside the window`() {
        val tracker = RageClickTracker(threshold = 3, windowMillis = 1_000)

        assertNull(tracker.record(100))
        assertNull(tracker.record(200))
        assertEquals(3, tracker.record(300))
        assertNull(tracker.record(400), "a reported burst starts a new window")
    }

    @Test
    fun `expired clicks are discarded`() {
        val tracker = RageClickTracker(threshold = 3, windowMillis = 100)

        assertNull(tracker.record(0))
        assertNull(tracker.record(50))
        assertNull(tracker.record(100))
        assertEquals(3, tracker.record(149))
    }

    @Test
    fun `rejects invalid tracker configuration`() {
        assertFailsWith<IllegalArgumentException> { RageClickTracker(threshold = 1) }
        assertFailsWith<IllegalArgumentException> { RageClickTracker(windowMillis = 0) }
    }
}

package bosca.analytics.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class ScrollDepthTrackerTest {
    @Test
    fun `scroll milestones emit once and maximum is bounded`() {
        val tracker = ScrollDepthTracker()

        assertEquals(emptyList(), tracker.update(-10))
        assertEquals(listOf(25), tracker.update(27))
        assertEquals(listOf(50, 75), tracker.update(80))
        assertEquals(emptyList(), tracker.update(40))
        assertEquals(listOf(90, 100), tracker.update(120))
        assertEquals(emptyList(), tracker.update(100))
        assertEquals(100, tracker.maximum)
    }

    @Test
    fun `custom milestones are supported`() {
        val tracker = ScrollDepthTracker(listOf(10, 20))

        assertEquals(listOf(10, 20), tracker.update(20))
        assertEquals(20, tracker.maximum)
    }
}

package bosca.analytics.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputFocusTrackerTest {
    @Test
    fun `tracks one focus period and its dwell`() {
        val tracker = InputFocusTracker()

        assertTrue(tracker.focus(100))
        assertFalse(tracker.focus(200))
        assertEquals(250, tracker.blur(350))
        assertNull(tracker.blur(400))
    }

    @Test
    fun `clock regression cannot create a negative dwell`() {
        val tracker = InputFocusTracker()

        tracker.focus(500)

        assertEquals(0, tracker.blur(400))
    }
}

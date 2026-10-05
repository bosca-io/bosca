package bosca.analytics.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigationTransitionTrackerTest {
    @Test
    fun `classifies initial push pop and replacement transitions`() {
        val tracker = NavigationTransitionTracker()

        assertEquals(
            NavigationTransition(null, "/home", "initial", 1),
            tracker.move(listOf("/home")),
        )
        assertNull(tracker.move(listOf("/home")))
        assertNull(tracker.move(emptyList()))
        assertEquals(
            NavigationTransition("/home", "/book", "push", 2),
            tracker.move(listOf("/home", "/book")),
        )
        assertEquals(
            NavigationTransition("/book", "/home", "pop", 1),
            tracker.move(listOf("/home")),
        )
        assertEquals(
            NavigationTransition("/home", "/settings", "replace", 1),
            tracker.move(listOf("/settings")),
        )
        assertEquals(
            NavigationTransition("/settings", "/details", "push", 2),
            tracker.move(listOf("/settings", "/details")),
        )
        assertEquals(
            NavigationTransition("/details", "/profile", "reset", 2),
            tracker.move(listOf("/account", "/profile")),
        )
    }
}

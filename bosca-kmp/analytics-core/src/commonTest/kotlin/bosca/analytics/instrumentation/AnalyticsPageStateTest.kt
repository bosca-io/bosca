package bosca.analytics.instrumentation

import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsEventInput
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.api.DefaultAnalyticsEventFactory
import bosca.analytics.api.Page
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsPageStateTest {
    @Test
    fun `latest composed screen supplies the default event page`() = runTest {
        val state = AnalyticsPageState()
        val first = Page(path = "/first")
        val second = Page(path = "/second")
        assertTrue(state.enter("first", first))
        assertTrue(state.enter("second", second))

        val event = DefaultAnalyticsEventFactory(state).createEvent(
            AnalyticsEventInput(AnalyticsEventType.IMPRESSION, AnalyticsElement("hero", "card")),
        )

        assertEquals(second, event.page)
        state.leave("second")
        assertEquals(first, state.current)
        state.leave("first")
        assertNull(state.current)
    }

    @Test
    fun `entering an existing screen moves its updated page to the top`() {
        val state = AnalyticsPageState()
        state.enter("library", Page(path = "/old"))
        state.enter("settings", Page(path = "/settings"))
        state.enter("library", Page(path = "/library"))

        assertEquals("/library", state.current?.path)
        state.clear()
        assertNull(state.current)
    }

    @Test
    fun `enter reports whether the visible path changed`() {
        val state = AnalyticsPageState()

        assertTrue(state.enter("first", Page(path = "/library", title = "Library")))
        assertFalse(state.enter("second", Page(path = "/library", title = "Updated")))
        assertTrue(state.enter("third", Page(path = "/settings")))
    }
}

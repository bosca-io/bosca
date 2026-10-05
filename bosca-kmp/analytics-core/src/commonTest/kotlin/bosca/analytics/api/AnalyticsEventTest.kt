package bosca.analytics.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class AnalyticsEventTest {
    @Test
    fun `default factory snapshots nested data and preserves explicit page`() = runTest {
        val content = mutableListOf(ContentElement("article", "story", index = 0, percent = 0.0))
        val extras = mutableMapOf("source" to "home")
        val input = AnalyticsEventInput(
            type = AnalyticsEventType.IMPRESSION,
            element = AnalyticsElement("hero", "card", content, extras),
            page = Page(path = "/home", title = "Home"),
        )

        val event = DefaultAnalyticsEventFactory(CurrentPageProvider { null }).createEvent(input)
        content.clear()
        extras.clear()

        assertEquals("impression", event.name)
        assertEquals(Page(path = "/home", title = "Home"), event.page)
        assertEquals(1, event.element.content.size)
        assertEquals("home", event.element.extras["source"])
        assertNotSame(input.element.content, event.element.content)
    }

    @Test
    fun `factory uses current page when input does not provide one`() = runTest {
        val page = Page(path = "/current")
        val event = DefaultAnalyticsEventFactory(CurrentPageProvider { page }).createEvent(
            AnalyticsEventInput(AnalyticsEventType.INTERACTION, AnalyticsElement("save", "button")),
        )

        assertEquals(page, event.page)
    }

    @Test
    fun `parameters include content zeroes extras and structured error fields`() {
        val event = event(
            type = AnalyticsEventType.ERROR,
            element = AnalyticsElement(
                id = "save",
                type = "button",
                content = listOf(ContentElement("doc", "document", index = 0, percent = 0.0)),
                extras = mapOf("mode" to "edit"),
            ),
            error = ErrorInfo("failed", type = "network", stackTrace = "trace", fatal = true, code = "E1"),
        )

        val parameters = event.toParameters()

        assertEquals(JsonPrimitive("error"), parameters["type"])
        assertEquals(JsonPrimitive(0), parameters["content_id_index_0"])
        assertEquals(JsonPrimitive(0.0), parameters["content_id_percent_0"])
        assertEquals(JsonPrimitive(true), parameters["error_fatal"])
        assertEquals(JsonPrimitive("trace"), parameters["error_stack_trace"])
        assertEquals(JsonPrimitive("edit"), parameters["extra_mode"])
    }

    @Test
    fun `parameters omit absent optional content and error details`() {
        val event = event(
            type = AnalyticsEventType.ERROR,
            element = AnalyticsElement(
                id = "save",
                type = "button",
                content = listOf(ContentElement("doc", "document")),
            ),
            error = ErrorInfo("failed"),
        )

        val parameters = event.toParameters()

        assertEquals(null, parameters["content_id_index_0"])
        assertEquals(null, parameters["content_id_percent_0"])
        assertEquals(null, parameters["error_type"])
        assertEquals(null, parameters["error_stack_trace"])
        assertEquals(null, parameters["error_code"])
        assertEquals(JsonPrimitive(false), parameters["error_fatal"])
        assertEquals(null, event(type = AnalyticsEventType.INTERACTION).toParameters()["error_message"])
    }

    private fun event(
        type: AnalyticsEventType,
        element: AnalyticsElement = AnalyticsElement("id", "type"),
        error: ErrorInfo? = null,
    ) = AnalyticsEvent(
        clientId = "event",
        type = type,
        created = 1,
        createdMicros = 2,
        element = element,
        error = error,
    )
}

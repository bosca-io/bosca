package bosca.analytics.instrumentation

import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventSink
import bosca.analytics.api.ContentElement
import bosca.analytics.api.Page
import bosca.analytics.testAnalytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsInstrumentationContextTest {
    @Test
    fun `nested coroutine contexts enrich manual analytics`() = runTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        val page = Page(path = "/library", title = "Library")
        val inheritedContent = ContentElement("library", "collection")
        val childContent = ContentElement("book", "item", index = 2)
        val eventContent = ContentElement("cover", "image")

        withAnalyticsContext(
            AnalyticsInstrumentationContext(
                page = page,
                elementType = "catalog",
                extras = mapOf("tenant" to "public", "layer" to "parent"),
                content = listOf(inheritedContent),
            ),
        ) {
            withAnalyticsContext(
                AnalyticsInstrumentationContext(
                    elementId = "selected-book",
                    extras = mapOf("layer" to "child"),
                    content = listOf(childContent),
                ),
            ) {
                analytics.logInteraction(
                    AnalyticsElement(
                        id = "generated-id",
                        type = "button",
                        content = listOf(eventContent),
                        extras = mapOf("layer" to "event"),
                    ),
                )
            }
        }

        val event = sink.events.single()
        assertEquals("selected-book", event.element.id)
        assertEquals("catalog", event.element.type)
        assertEquals(page, event.page)
        assertEquals(listOf(inheritedContent, childContent, eventContent), event.element.content)
        assertEquals(mapOf("tenant" to "public", "layer" to "event"), event.element.extras)
        analytics.close()
    }

    @Test
    fun `owned analytics scope reports failures with its context`() = runTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        val scope = analytics.analyticsCoroutineScope(
            analytics = AnalyticsInstrumentationContext(
                page = Page(path = "/worker"),
                extras = mapOf("task" to "sync"),
            ),
        )

        scope.launch { throw IllegalStateException("worker failed") }
        withTimeout(2_000) {
            while (sink.events.isEmpty()) delay(1)
        }

        val event = sink.events.single()
        assertEquals("worker failed", event.error?.message)
        assertEquals("/worker", event.page?.path)
        assertEquals("sync", event.element.extras["task"])
        scope.launch { throw CancellationException("cancelled") }
        scope.cancel()
        analytics.close()
    }

    private class RecordingSink : AnalyticsEventSink() {
        val events = mutableListOf<AnalyticsEvent>()

        override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
            events += event
        }
    }
}

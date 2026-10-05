package bosca.analytics.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventSink
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.api.AnalyticsNavigationRoute
import bosca.analytics.api.Page
import bosca.analytics.instrumentation.AnalyticsInstrumentationContext
import bosca.analytics.testAnalytics
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ComposeNavigationTest {
    @Test
    fun `navigation page is inherited by automatically instrumented controls`() = runComposeUiTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        var click: (() -> Unit)? = null

        setContent {
            ProvideAnalytics(analytics) {
                AutoInstrumentedNavigation(listOf(BookRoute("42")), "sample.App")
                click = rememberAutoInstrumentedAction(
                    onAction = {},
                    id = "checkout/button/1",
                    elementType = "button",
                    action = "click",
                    source = "sample.CheckoutScreen",
                    pagePath = "",
                    pageTitle = "",
                )
            }
        }
        waitUntil { sink.impressions().size == 1 }

        runOnIdle { click?.invoke() }
        waitUntil { sink.events.any { it.element.id == "checkout/button/1" } }

        val interaction = sink.events.single { it.element.id == "checkout/button/1" }
        assertEquals("/books/42", interaction.page?.path)
        assertEquals("Book", interaction.page?.title)
        analytics.close()
    }

    @Test
    fun `navigation back stack records destinations and transition direction`() = runComposeUiTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        var backStack by mutableStateOf<List<Any>>(emptyList())

        setContent {
            ProvideAnalytics(analytics) {
                ProvideAnalyticsContext(
                    AnalyticsInstrumentationContext(
                        page = Page(url = "app://shell", title = "Shell"),
                        elementId = "shell-route",
                        elementType = "destination",
                        extras = mapOf("shell" to "main"),
                    ),
                ) {
                    AutoInstrumentedNavigation(backStack, "sample.App")
                }
            }
        }
        awaitIdle()
        assertEquals(0, sink.events.size)

        runOnIdle { backStack = listOf(HomeRoute) }
        waitUntil { sink.navigationEvents().size == 1 && sink.impressions().size == 1 }

        runOnIdle { backStack = listOf(HomeAliasRoute) }
        awaitIdle()
        assertEquals(1, sink.navigationEvents().size)
        assertEquals(1, sink.impressions().size)

        runOnIdle { backStack = listOf(HomeRoute, BookRoute("42")) }
        waitUntil { sink.navigationEvents().size == 2 && sink.impressions().size == 2 }

        runOnIdle { backStack = listOf(HomeRoute) }
        waitUntil { sink.navigationEvents().size == 3 && sink.impressions().size == 3 }

        assertEquals(listOf("initial", "push", "pop"), sink.navigationEvents().map { it.element.extras["action"] })
        assertEquals(listOf("/home", "/books/42", "/home"), sink.navigationEvents().map { it.page?.path })
        val book = sink.navigationEvents()[1]
        assertEquals("/home", book.element.extras["from"])
        assertEquals("2", book.element.extras["depth"])
        assertEquals("42", book.element.extras["book_id"])
        assertEquals("main", book.element.extras["shell"])
        assertEquals("navigation3", book.element.extras["instrumentation"])
        assertEquals("app://shell", book.page?.url)
        assertEquals("shell-route", sink.impressions()[1].element.id)
        assertEquals("destination", sink.impressions()[1].element.type)
        analytics.close()
    }

    private data object HomeRoute : AnalyticsNavigationRoute {
        override val analyticsPath = "/home"
    }

    private data object HomeAliasRoute : AnalyticsNavigationRoute {
        override val analyticsPath = "/home"
    }

    private data class BookRoute(val id: String) : AnalyticsNavigationRoute {
        override val analyticsPath = "/books/$id"
        override val analyticsTitle = "Book"
        override val analyticsExtras = mapOf("book_id" to id)
    }

    private class RecordingSink : AnalyticsEventSink() {
        val events = CopyOnWriteArrayList<AnalyticsEvent>()

        fun navigationEvents() = events.filter { it.element.type == "navigation" }

        fun impressions() = events.filter { it.type == AnalyticsEventType.IMPRESSION }

        override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
            events += event
        }
    }
}

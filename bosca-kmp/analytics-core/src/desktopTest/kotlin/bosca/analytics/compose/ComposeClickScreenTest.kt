package bosca.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import bosca.analytics.testAnalytics
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventSink
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.api.ContentElement
import bosca.analytics.api.Page
import bosca.analytics.instrumentation.AnalyticsInstrumentationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class ComposeClickScreenTest {
    @Test
    fun `screen clicks and coroutine failures inherit composition context`() = runComposeUiTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        val content = ContentElement("book", "item")
        var click: (() -> Unit)? = null
        var fallbackClick: (() -> Unit)? = null
        var composeScope: CoroutineScope? = null
        var invoked = false
        var shown by mutableStateOf(true)

        setContent {
            ProvideAnalytics(analytics) {
                if (shown) {
                    InstrumentedContent(
                        content = content,
                        onClickReady = { click = it },
                        onFallbackClickReady = { fallbackClick = it },
                        onScopeReady = { composeScope = it },
                        onInvoked = { invoked = true },
                    )
                }
            }
        }
        awaitIdle()
        waitUntil { sink.events.count { it.type == AnalyticsEventType.IMPRESSION } == 2 }

        runOnIdle {
            click?.invoke()
            fallbackClick?.invoke()
        }
        waitUntil {
            sink.events.count { it.type == AnalyticsEventType.INTERACTION && it.error == null } == 2
        }
        assertEquals(true, invoked)

        runOnIdle {
            composeScope?.launch { error("compose failed") }
        }
        waitUntil { sink.events.any { it.error?.message == "compose failed" } }

        val impression = sink.events.single { it.element.extras["source"] == "sample.CheckoutScreen" }
        assertEquals("checkout-context", impression.element.id)
        assertEquals("purchase", impression.element.type)
        assertEquals("/checkout", impression.page?.path)
        assertEquals("Checkout", impression.page?.title)
        assertEquals("app://checkout", impression.page?.url)
        assertEquals("compact", impression.element.extras["experiment"])
        assertEquals(listOf(content), impression.element.content)
        val fallbackImpression = sink.events.single { it.element.extras["source"] == "sample.FallbackScreen" }
        assertEquals("fallback", fallbackImpression.element.id)
        assertEquals("page", fallbackImpression.element.type)
        assertEquals("fallback", fallbackImpression.page?.path)
        assertNull(fallbackImpression.page?.title)

        val interaction = sink.events.single { it.element.extras["source"] == "sample.CheckoutScreen.button" }
        assertEquals("checkout-context", interaction.element.id)
        assertEquals("purchase", interaction.element.type)
        assertEquals("/checkout", interaction.page?.path)
        val fallbackInteraction = sink.events.single {
            it.element.extras["source"] == "sample.FallbackScreen.button"
        }
        assertEquals("fallback-button", fallbackInteraction.element.id)
        assertEquals("button", fallbackInteraction.element.type)
        assertEquals("fallback", fallbackInteraction.page?.path)

        val failure = sink.events.single { it.error?.message == "compose failed" }
        assertEquals("/inherited", failure.page?.path)
        assertEquals("compact", failure.element.extras["experiment"])

        runOnIdle { shown = false }
        awaitIdle()
        analytics.close()
    }

    @Test
    fun `route recomposition records the new screen and scopes its controls`() = runComposeUiTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        var route by mutableStateOf("library")
        var title by mutableStateOf("Library")
        var click: (() -> Unit)? = null

        setContent {
            ProvideAnalytics(analytics) {
                val path = "/$route"
                ProvideAnalyticsContext(
                    AnalyticsInstrumentationContext(extras = mapOf("route" to route)),
                ) {
                    AutoInstrumentedScreen(
                        id = route,
                        path = path,
                        title = title,
                        source = "sample.${route}Screen",
                    )
                    click = rememberAutoInstrumentedClick(
                        onClick = {},
                        id = "$route-button",
                        elementType = "button",
                        source = "sample.${route}Screen.button",
                        pagePath = path,
                        pageTitle = title,
                    )
                }
            }
        }
        awaitIdle()
        waitUntil { sink.events.any { it.page?.path == "/library" } }

        runOnIdle { title = "Updated Library" }
        awaitIdle()
        assertEquals(1, sink.events.count { it.type == AnalyticsEventType.IMPRESSION })

        runOnIdle {
            route = "details"
            title = "Details"
        }
        awaitIdle()
        waitUntil { sink.events.any { it.page?.path == "/details" } }
        runOnIdle { click?.invoke() }
        waitUntil {
            sink.events.any {
                it.type == AnalyticsEventType.INTERACTION && it.element.id == "details-button"
            }
        }

        val interaction = sink.events.single {
            it.type == AnalyticsEventType.INTERACTION && it.element.id == "details-button"
        }
        assertEquals("/details", interaction.page?.path)
        assertEquals("details", interaction.element.extras["route"])
        analytics.close()
    }

    @Composable
    private fun InstrumentedContent(
        content: ContentElement,
        onClickReady: (() -> Unit) -> Unit,
        onFallbackClickReady: (() -> Unit) -> Unit,
        onScopeReady: (CoroutineScope) -> Unit,
        onInvoked: () -> Unit,
    ) {
        ProvideAnalyticsContext(
            AnalyticsInstrumentationContext(
                page = Page(path = "/inherited", url = "app://checkout", title = "Inherited"),
                elementId = "checkout-context",
                elementType = "purchase",
                extras = mapOf("experiment" to "compact"),
                content = listOf(content),
            ),
        ) {
            AutoInstrumentedScreen(
                id = "checkout",
                path = "/checkout",
                title = "Checkout",
                source = "sample.CheckoutScreen",
            )
            onClickReady(
                rememberAutoInstrumentedClick(
                    onClick = onInvoked,
                    id = "submit",
                    elementType = "button",
                    source = "sample.CheckoutScreen.button",
                    pagePath = "/checkout",
                    pageTitle = "Checkout",
                ),
            )
            onScopeReady(rememberAnalyticsCoroutineScope())
        }
        AutoInstrumentedScreen(
            id = "fallback",
            path = "",
            title = "",
            source = "sample.FallbackScreen",
        )
        onFallbackClickReady(
            rememberAutoInstrumentedClick(
                onClick = {},
                id = "fallback-button",
                elementType = "button",
                source = "sample.FallbackScreen.button",
                pagePath = "",
                pageTitle = "",
            ),
        )
    }

    private class RecordingSink : AnalyticsEventSink() {
        val events = CopyOnWriteArrayList<AnalyticsEvent>()

        override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
            events += event
        }
    }
}

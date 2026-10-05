package bosca.analytics.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventSink
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.instrumentation.AnalyticsInstrumentationContext
import bosca.analytics.testAnalytics
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class ComposeActionTest {
    @Test
    fun `action wrappers preserve callbacks and emit semantic interaction details`() = runComposeUiTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        var clicks = 0
        var selected: Boolean? = null
        var click: (() -> Unit)? = null
        var longClick: (() -> Unit)? = null
        var change: ((Boolean) -> Unit)? = null
        var disabledAction: (() -> Unit)? = { error("not initialized") }
        var disabledChange: ((Boolean) -> Unit)? = { error("not initialized") }
        var enabledAction: (() -> Unit)? = null
        var enabledChange: ((Boolean) -> Unit)? = null
        var variant by androidx.compose.runtime.mutableStateOf(false)

        setContent {
            ProvideAnalytics(analytics) {
                val suffix = if (variant) "-updated" else ""
                ProvideAnalyticsContext(AnalyticsInstrumentationContext(extras = mapOf("flow" to "checkout"))) {
                    click = rememberAutoInstrumentedAction(
                        onAction = { clicks++ },
                        id = "buy",
                        elementType = "button",
                        action = "click",
                        source = "sample.Checkout.buy",
                        pagePath = "/checkout",
                        pageTitle = "Checkout",
                    )
                    longClick = rememberAutoInstrumentedAction(
                        onAction = { clicks++ },
                        id = "buy",
                        elementType = "long_click",
                        action = "long_click",
                        source = "sample.Checkout.buy",
                        pagePath = "/checkout",
                        pageTitle = "Checkout",
                    )
                    change = rememberAutoInstrumentedBooleanChange(
                        onChange = { selected = it },
                        id = "gift$suffix",
                        elementType = if (variant) "switch" else "checkbox",
                        source = "sample.Checkout.gift$suffix",
                        pagePath = if (variant) "/updated-checkout" else "/checkout",
                        pageTitle = if (variant) "Updated Checkout" else "Checkout",
                    )
                    disabledAction = rememberAutoInstrumentedNullableAction(
                        null, "disabled", "button", "click", "sample.Disabled", "", "",
                    )
                    disabledChange = rememberAutoInstrumentedNullableBooleanChange(
                        null, "disabled", "checkbox", "sample.Disabled", "", "",
                    )
                    enabledAction = rememberAutoInstrumentedNullableAction(
                        { clicks++ }, "enabled-action", "menu_item", "select", "sample.Menu", "", "",
                    )
                    enabledChange = rememberAutoInstrumentedNullableBooleanChange(
                        { selected = it }, "enabled-change", "switch", "sample.Switch", "", "",
                    )
                }
            }
        }
        awaitIdle()

        runOnIdle {
            repeat(3) { click?.invoke() }
            longClick?.invoke()
            change?.invoke(true)
            enabledAction?.invoke()
            enabledChange?.invoke(false)
        }
        waitUntil { sink.events.size == 8 }

        assertEquals(5, clicks)
        assertEquals(false, selected)
        assertNull(disabledAction)
        assertNull(disabledChange)
        assertEquals(3, sink.events.count { it.element.type == "button" })
        assertEquals(1, sink.events.count { it.element.type == "rage_click" })
        assertEquals("3", sink.events.single { it.element.type == "rage_click" }.element.extras["click_count"])
        assertEquals("long_click", sink.events.single { it.element.type == "long_click" }.element.extras["action"])
        assertEquals("true", sink.events.single { it.element.type == "checkbox" }.element.extras["selected"])
        assertEquals("select", sink.events.single { it.element.id == "enabled-action" }.element.extras["action"])
        assertEquals("false", sink.events.single { it.element.id == "enabled-change" }.element.extras["selected"])
        assertEquals("/checkout", sink.events.first().page?.path)
        assertEquals("checkout", sink.events.first().element.extras["flow"])
        runOnIdle { variant = true }
        awaitIdle()
        analytics.close()
    }

    private class RecordingSink : AnalyticsEventSink() {
        val events = CopyOnWriteArrayList<AnalyticsEvent>()

        override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
            require(event.type == AnalyticsEventType.INTERACTION)
            events += event
        }
    }
}

package bosca.analytics.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventSink
import bosca.analytics.testAnalytics
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ComposeInputTest {
    @Test
    fun `input focus blur and disposal are instrumented without field values`() = runComposeUiTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        val firstFocus = FocusRequester()
        val secondFocus = FocusRequester()
        var showSecond by mutableStateOf(true)
        var variant by mutableStateOf(false)

        setContent {
            ProvideAnalytics(analytics) {
                val suffix = if (variant) "-updated" else ""
                autoInstrumentedInputModifier(
                    modifier = if (variant) Modifier else null,
                    id = "unmounted$suffix",
                    source = "sample.Form.unmounted$suffix",
                    pagePath = if (variant) "/updated-form" else "",
                    pageTitle = if (variant) "Updated Form" else "",
                )
                Column {
                    BasicTextField(
                        value = "secret-one",
                        onValueChange = {},
                        modifier = autoInstrumentedInputModifier(
                            modifier = Modifier.focusRequester(firstFocus).size(40.dp),
                            id = "first",
                            source = "sample.Form.first",
                            pagePath = "/form",
                            pageTitle = "Form",
                        ),
                    )
                    if (showSecond) {
                        BasicTextField(
                            value = "secret-two",
                            onValueChange = {},
                            modifier = autoInstrumentedInputModifier(
                                modifier = Modifier.focusRequester(secondFocus).size(40.dp),
                                id = "second",
                                source = "sample.Form.second",
                                pagePath = "/form",
                                pageTitle = "Form",
                            ),
                        )
                    }
                }
            }
        }
        awaitIdle()

        runOnIdle { firstFocus.requestFocus() }
        waitUntil { sink.events.any { it.element.type == "field_focus" && it.element.id == "first" } }
        runOnIdle { secondFocus.requestFocus() }
        waitUntil { sink.events.any { it.element.type == "field_blur" && it.element.id == "first" } }
        runOnIdle { showSecond = false }
        waitUntil { sink.events.any { it.element.type == "field_abandon" && it.element.id == "second" } }

        assertEquals(
            setOf("field_focus", "field_blur"),
            sink.events.filter { it.element.id == "first" }.map { it.element.type }.toSet(),
        )
        assertEquals(
            setOf("field_focus", "field_abandon"),
            sink.events.filter { it.element.id == "second" }.map { it.element.type }.toSet(),
        )
        assertEquals(true, sink.events.none { event -> event.toString().contains("secret-") })
        assertEquals("/form", sink.events.first().page?.path)
        runOnIdle { variant = true }
        awaitIdle()
        analytics.close()
    }

    private class RecordingSink : AnalyticsEventSink() {
        val events = CopyOnWriteArrayList<AnalyticsEvent>()

        override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
            events += event
        }
    }
}

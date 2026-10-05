package bosca.analytics.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
class ComposeVisibilityTest {
    @Test
    fun `visibility records one impression after content enters the viewport`() = runComposeUiTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        var visible by mutableStateOf(false)
        var variant by mutableStateOf(false)

        setContent {
            ProvideAnalytics(analytics) {
                val offset = if (visible) 0.dp else 10_000.dp
                val suffix = if (variant) "-updated" else ""
                Box(
                    autoInstrumentedVisibilityModifier(
                        modifier = null,
                        id = "unmeasured",
                        elementType = "content",
                        source = "sample.Home.unmeasured",
                        pagePath = "",
                        pageTitle = "",
                        threshold = 1f,
                        dwellMillis = 1,
                    ),
                )
                Box(
                    autoInstrumentedVisibilityModifier(
                        modifier = Modifier.offset(x = offset).size(40.dp),
                        id = "recommendations$suffix",
                        elementType = if (variant) "updated_carousel" else "carousel",
                        source = "sample.Home.recommendations$suffix",
                        pagePath = if (variant) "/updated-home" else "/home",
                        pageTitle = if (variant) "Updated Home" else "Home",
                        threshold = if (variant) 0.75f else 0.5f,
                        dwellMillis = if (variant) 1 else 0,
                    ),
                )
            }
        }
        awaitIdle()
        assertEquals(0, sink.events.size)

        runOnIdle { visible = true }
        waitUntil { sink.events.size == 1 }
        runOnIdle { variant = true }
        waitUntil { sink.events.size == 2 }
        runOnIdle { visible = false }
        awaitIdle()
        runOnIdle { visible = true }
        awaitIdle()

        assertEquals(2, sink.events.size)
        val event = sink.events.single { it.element.id == "recommendations" }
        assertEquals("recommendations", event.element.id)
        assertEquals("carousel", event.element.type)
        assertEquals("0.5", event.element.extras["visibility_threshold"])
        assertEquals("0", event.element.extras["dwell_ms"])
        assertEquals("/home", event.page?.path)
        val updated = sink.events.single { it.element.id == "recommendations-updated" }
        assertEquals("updated_carousel", updated.element.type)
        assertEquals("0.75", updated.element.extras["visibility_threshold"])
        assertEquals("/updated-home", updated.page?.path)
        analytics.close()
    }

    private class RecordingSink : AnalyticsEventSink() {
        val events = CopyOnWriteArrayList<AnalyticsEvent>()

        override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
            events += event
        }
    }
}

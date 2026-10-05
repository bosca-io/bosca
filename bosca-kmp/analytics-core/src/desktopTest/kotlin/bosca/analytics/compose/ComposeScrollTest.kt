package bosca.analytics.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventSink
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.instrumentation.AnalyticsInstrumentationContext
import bosca.analytics.testAnalytics
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ComposeScrollTest {
    @Test
    fun `unscoped scroll uses generated identity and a page without a title`() = runComposeUiTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        val scrollState = ScrollState(0)
        var shown by mutableStateOf(true)

        setContent {
            ProvideAnalytics(analytics) {
                if (shown) {
                    val tracked = autoInstrumentedScrollState(
                        state = scrollState,
                        id = "plain-scroll",
                        axis = "vertical",
                        source = "sample.PlainScreen",
                        pagePath = "/plain",
                        pageTitle = "",
                    )
                    Column(Modifier.size(40.dp).verticalScroll(tracked)) {
                        Spacer(Modifier.fillMaxWidth().height(400.dp))
                    }
                }
            }
        }
        awaitIdle()
        scrollState.scrollTo(scrollState.maxValue)
        awaitIdle()
        waitUntil { sink.events.size >= 5 }
        runOnIdle { shown = false }
        awaitIdle()
        waitUntil { sink.events.size == 6 }

        assertTrue(sink.events.all { it.element.id == "plain-scroll" })
        assertTrue(sink.events.any { it.element.type == "scroll_depth" })
        assertEquals(null, sink.events.first().page?.title)
        analytics.close()
    }

    @Test
    fun `regular and lazy scrolling emit milestones and maximum depth`() = runComposeUiTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        val scrollState = ScrollState(0)
        var lazyState: androidx.compose.foundation.lazy.LazyListState? = null
        var shown by mutableStateOf(true)

        setContent {
            ProvideAnalytics(analytics) {
                if (shown) {
                    Column(Modifier.size(100.dp)) {
                        ProvideAnalyticsContext(
                            AnalyticsInstrumentationContext(
                                elementId = "article-context",
                                elementType = "article",
                                extras = mapOf("section" to "body"),
                            ),
                        ) {
                            val trackedScroll = autoInstrumentedScrollState(
                                state = scrollState,
                                id = "article-scroll",
                                axis = "vertical",
                                source = "sample.ArticleScreen",
                                pagePath = "/article",
                                pageTitle = "Article",
                            )
                            Column(Modifier.size(40.dp).verticalScroll(trackedScroll)) {
                                Spacer(Modifier.fillMaxWidth().height(400.dp))
                            }
                        }
                        ProvideAnalyticsContext(
                            AnalyticsInstrumentationContext(
                                elementId = "feed-context",
                                elementType = "feed",
                            ),
                        ) {
                            val trackedLazy = autoInstrumentedLazyListState(
                                state = null,
                                id = "feed-scroll",
                                axis = "vertical",
                                source = "sample.FeedScreen",
                                pagePath = "/feed",
                                pageTitle = "Feed",
                            )
                            lazyState = trackedLazy
                            LazyColumn(Modifier.size(40.dp), state = trackedLazy) {
                                items(100) { Spacer(Modifier.fillMaxWidth().height(20.dp)) }
                            }
                        }
                    }
                }
            }
        }
        awaitIdle()
        scrollState.scrollTo(scrollState.maxValue)
        lazyState?.scrollToItem(99)
        awaitIdle()
        waitUntil { sink.events.size >= 10 }

        val depthEvents = sink.events.toList()
        assertTrue(depthEvents.all { it.type == AnalyticsEventType.INTERACTION })
        assertEquals(setOf("article-context", "feed-context"), depthEvents.map { it.element.id }.toSet())
        assertEquals(setOf("scroll_depth"), depthEvents.map { it.element.type }.toSet())
        assertEquals(setOf("25", "50", "75", "90", "100"), depthEvents.mapNotNull { it.element.extras["depth_percent"] }.toSet())
        assertTrue(depthEvents.all { it.element.extras["axis"] == "vertical" })
        assertTrue(depthEvents.filter { it.element.id == "article-context" }.all {
            it.element.extras["section"] == "body"
        })
        assertTrue(depthEvents.filter { it.element.id == "article-context" }.all {
            it.element.extras["target_element_type"] == "article"
        })
        assertTrue(depthEvents.filter { it.element.id == "feed-context" }.all {
            it.element.extras["target_element_type"] == "feed"
        })

        runOnIdle { shown = false }
        awaitIdle()
        waitUntil { sink.events.size == 12 }
        assertEquals(6, sink.events.count { it.element.id == "article-context" })
        assertEquals(6, sink.events.count { it.element.id == "feed-context" })
        assertEquals(2, sink.events.count { it.element.type == "scroll_max_depth" })
        analytics.close()
    }

    @Test
    fun `empty scroll surfaces remain silent and accept existing lazy state`() = runComposeUiTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        val scrollState = ScrollState(0)
        var shown by mutableStateOf(true)
        var variant by mutableStateOf(false)

        setContent {
            ProvideAnalytics(analytics) {
                if (shown) {
                    ProvideAnalyticsContext(
                        AnalyticsInstrumentationContext(
                            extras = if (variant) mapOf("variant" to "second") else emptyMap(),
                        ),
                    ) {
                        autoInstrumentedScrollState(
                            state = scrollState,
                            id = if (variant) "empty-scroll-2" else "empty-scroll",
                            axis = if (variant) "vertical" else "horizontal",
                            source = if (variant) "sample.EmptyRoute" else "sample.EmptyScreen",
                            pagePath = if (variant) "/empty" else "",
                            pageTitle = if (variant) "Empty" else "",
                        )
                        val existing = rememberLazyListState()
                        val tracked = autoInstrumentedLazyListState(
                            state = existing,
                            id = if (variant) "empty-list-2" else "empty-list",
                            axis = if (variant) "vertical" else "horizontal",
                            source = if (variant) "sample.EmptyRoute" else "sample.EmptyScreen",
                            pagePath = if (variant) "/empty" else "",
                            pageTitle = if (variant) "Empty" else "",
                        )
                        LazyColumn(Modifier.size(40.dp), state = tracked) {}
                    }
                }
            }
        }
        awaitIdle()
        assertTrue(sink.events.isEmpty())
        runOnIdle { variant = true }
        awaitIdle()
        assertTrue(sink.events.isEmpty())
        runOnIdle { shown = false }
        awaitIdle()
        assertTrue(sink.events.isEmpty())
        analytics.close()
    }

    private class RecordingSink : AnalyticsEventSink() {
        val events = CopyOnWriteArrayList<AnalyticsEvent>()

        override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
            events += event
        }
    }
}

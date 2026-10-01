package bosca.analytics.api

import bosca.analytics.instrumentation.AnalyticsInstrumentationContext
import bosca.analytics.testAnalytics
import bosca.analytics.testRuntimeScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AnalyticsTest {
    @Test
    fun `logging applies sink interceptors and facade helpers`() = runTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        sink.addInterceptor { event ->
            event.copy(element = event.element.copy(extras = event.element.extras + ("first" to "true")))
        }

        analytics.logImpression(AnalyticsElement("hero", "card"))
        analytics.logInteraction(AnalyticsElement("save", "button"))
        analytics.logCompletion(AnalyticsElement("flow", "workflow"))
        analytics.logError(ErrorInfo("failed"))

        assertEquals(
            listOf(
                AnalyticsEventType.IMPRESSION,
                AnalyticsEventType.INTERACTION,
                AnalyticsEventType.COMPLETION,
                AnalyticsEventType.ERROR,
            ),
            sink.events.map { it.type },
        )
        assertEquals("true", sink.events.first().element.extras["first"])
        analytics.close()
    }

    @Test
    fun `automatic event failures are reported asynchronously`() = runTest {
        var failure: Throwable? = null
        val analytics = testAnalytics(
            sink = object : AnalyticsEventSink() {
                override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
                    error("sink failed")
                }
            },
            runtimeScope = testRuntimeScope(this),
            onAutomaticError = { failure = it },
        )

        analytics.recordAutomaticEvent(
            AnalyticsEventInput(AnalyticsEventType.INTERACTION, AnalyticsElement("save", "button")),
        )

        withTimeout(2_000) {
            while (failure == null) delay(1)
        }
        assertEquals("sink failed", failure?.message)
        analytics.close()

        var cancellationFailure: Throwable? = null
        val cancelled = testAnalytics(
            sink = object : AnalyticsEventSink() {
                override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
                    throw CancellationException("cancelled")
                }
            },
            runtimeScope = testRuntimeScope(this),
            onAutomaticError = { cancellationFailure = it },
        )
        cancelled.recordAutomaticEvent(
            AnalyticsEventInput(AnalyticsEventType.INTERACTION, AnalyticsElement("cancel", "button")),
        )
        delay(10)
        assertNull(cancellationFailure)
        cancelled.close()
    }

    @Test
    fun `automatic pages emit one impression for the active destination`() = runTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink, runtimeScope = testRuntimeScope(this))

        analytics.recordAutomaticPage(Any(), Page(path = "/library"), AnalyticsElement("library", "page"))
        analytics.recordAutomaticPage(Any(), Page(path = "/library"), AnalyticsElement("duplicate", "page"))
        analytics.recordAutomaticPage(Any(), Page(path = "/details"), AnalyticsElement("details", "page"))
        withTimeout(2_000) {
            while (sink.events.size < 2) delay(1)
        }

        assertEquals(setOf("library", "details"), sink.events.map { it.element.id }.toSet())
        assertEquals(setOf("/library", "/details"), sink.events.mapNotNull { it.page?.path }.toSet())
        analytics.close()
    }

    @Test
    fun `unhandled exceptions include failure and inherited instrumentation context`() = runTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink, runtimeScope = testRuntimeScope(this))

        analytics.recordUnhandledException(
            IllegalStateException("failed"),
            fatal = true,
            kind = "uncaught_exception",
            context = AnalyticsInstrumentationContext(
                page = Page(path = "/checkout"),
                extras = mapOf("flow" to "purchase"),
            ),
        )

        withTimeout(2_000) {
            while (sink.events.isEmpty()) delay(1)
        }
        val event = sink.events.single()
        assertEquals(AnalyticsEventType.ERROR, event.type)
        assertEquals("failed", event.error?.message)
        assertEquals(true, event.error?.fatal)
        assertEquals("/checkout", event.page?.path)
        assertEquals("purchase", event.element.extras["flow"])
        assertEquals("automatic", event.element.extras["instrumentation"])
        analytics.close()
    }

    @Test
    fun `client-core analytics bridge uses the injected service`() = runTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink, runtimeScope = testRuntimeScope(this))
        val platformAnalytics: bosca.core.platform.Analytics = analytics

        platformAnalytics.logEvent("opened", mapOf("count" to 2))
        platformAnalytics.logError("failed", type = "network", code = "E1")
        withTimeout(2_000) {
            while (sink.events.size < 2) delay(1)
        }

        assertEquals(setOf("opened", "network"), sink.events.map { it.element.id }.toSet())
        assertEquals("2", sink.events.single { it.element.id == "opened" }.element.extras["count"])
        assertEquals("E1", sink.events.single { it.element.id == "network" }.error?.code)
        analytics.close()
    }

    @Test
    fun `nameless errors and nullable bridge types retain useful fallbacks`() = runTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink, runtimeScope = testRuntimeScope(this))
        val nameless = object : Throwable() {
            override fun toString(): String = "nameless failure"
        }

        analytics.logUnhandledException(nameless, fatal = false, kind = "coroutine")
        val platformAnalytics: bosca.core.platform.Analytics = analytics
        platformAnalytics.logError("bridge failure", type = null)
        withTimeout(2_000) {
            while (sink.events.size < 2) delay(1)
        }

        assertEquals("", sink.events.first().element.id)
        assertEquals("nameless failure", sink.events.first().error?.message)
        assertEquals("", sink.events.last().element.id)
        analytics.close()
    }

    @Test
    fun `removing one interceptor preserves the remaining interceptor`() = runTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        val removed = AnalyticsEventInterceptor { event ->
            event.copy(element = event.element.copy(extras = event.element.extras + ("removed" to "true")))
        }
        val retained = AnalyticsEventInterceptor { event ->
            event.copy(element = event.element.copy(extras = event.element.extras + ("retained" to "true")))
        }
        sink.addInterceptor(removed)
        sink.addInterceptor(retained)
        sink.removeInterceptor(removed)

        analytics.logInteraction(AnalyticsElement("save", "button"))

        assertNull(sink.events.single().element.extras["removed"])
        assertEquals("true", sink.events.single().element.extras["retained"])
        analytics.close()
    }

    @Test
    fun `lifecycle starts explicitly and closes idempotently`() = runTest {
        val analytics = testAnalytics(RecordingSink())

        analytics.start()
        analytics.start()
        analytics.close()
        analytics.close()

        assertFailsWith<IllegalStateException> { analytics.start() }
    }

    private class RecordingSink : AnalyticsEventSink() {
        val events = mutableListOf<AnalyticsEvent>()

        override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
            events += event
        }
    }
}

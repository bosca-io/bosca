package bosca.analytics.instrumentation

import bosca.analytics.testAnalytics
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventSink
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class AutomaticInstrumentationTest {
    @Test
    fun `late enabled registration captures flushes throttles and restores`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val forwarded = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, error -> forwarded += error }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        val sink = RecordingSink()
        val analytics = testAnalytics(
            sink = sink,
            automaticInstrumentation = AutomaticInstrumentationOptions(
                captureUnhandledExceptions = true,
                maxErrorsPerMinute = 1,
                crashFlushTimeout = 1.seconds,
            ),
        )
        try {
            assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
            analytics.start()
            val installed = Thread.getDefaultUncaughtExceptionHandler()
            assertNotSame(previous, installed)

            val first = IllegalStateException("first")
            val throttled = IllegalArgumentException("throttled")
            installed?.uncaughtException(Thread.currentThread(), first)
            installed?.uncaughtException(Thread.currentThread(), throttled)

            assertEquals(listOf<Throwable>(first, throttled), forwarded)
            assertEquals(listOf("first"), sink.events.map { it.error?.message })
            assertEquals(1, sink.flushes)

            kotlinx.coroutines.runBlocking { analytics.close() }
            assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            kotlinx.coroutines.runBlocking { analytics.close() }
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    private class RecordingSink : AnalyticsEventSink() {
        val events = CopyOnWriteArrayList<AnalyticsEvent>()
        var flushes = 0

        override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
            events += event
        }

        override suspend fun flush() {
            flushes++
        }
    }
}

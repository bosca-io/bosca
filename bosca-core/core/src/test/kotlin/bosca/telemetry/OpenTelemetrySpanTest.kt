package bosca.telemetry

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Scope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenTelemetrySpanTest {

    private val scope = mockk<Scope>(relaxed = true)
    private val span = mockk<Span>(relaxed = true)
    private val builder = mockk<SpanBuilder>()
    private val tracer = mockk<Tracer>()

    init {
        every { tracer.spanBuilder(any()) } returns builder
        every { builder.setParent(any()) } returns builder
        every { builder.setAllAttributes(any()) } returns builder
        every { builder.startSpan() } returns span
        every { span.makeCurrent() } returns scope
    }

    @Test
    fun `suspending span returns values and records failures`() = runTest {
        assertEquals("ok", tracer.withSpan("success", Attributes.empty()) { "ok" })
        verify { span.end() }

        val failure = IllegalStateException("failure")
        assertFailsWith<IllegalStateException> {
            tracer.withSpan("failure") { throw failure }
        }
        verify { span.recordException(any<IllegalStateException>()) }
        verify(atLeast = 2) { span.end() }
    }

    @Test
    fun `synchronous span returns values and records failures`() {
        assertEquals(7, tracer.withSyncSpan("success") { 7 })
        val failure = IllegalArgumentException("failure")
        assertFailsWith<IllegalArgumentException> {
            tracer.withSyncSpan("failure") { throw failure }
        }
        verify { span.recordException(failure) }
        verify(atLeast = 2) { span.end() }
    }
}

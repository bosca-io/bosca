package bosca.server.middleware

import bosca.server.HttpMethod
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.semconv.HttpAttributes
import io.opentelemetry.semconv.UrlAttributes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class TracingMiddlewareTest {

    private val span = mockk<Span>(relaxed = true)
    private val builder = mockk<SpanBuilder>()
    private val tracer = mockk<Tracer>()

    init {
        every { tracer.spanBuilder(any()) } returns builder
        every { builder.setSpanKind(any()) } returns builder
        every { builder.setAttribute(any<io.opentelemetry.api.common.AttributeKey<String>>(), any<String>()) } returns builder
        every { builder.startSpan() } returns span
    }

    private fun call(status: HttpStatusCode? = null): ServerCall {
        val request = mockk<ServerRequest>()
        every { request.httpMethod } returns HttpMethod.Get
        every { request.path } returns "/items/123"
        val response = mockk<ServerResponse>()
        every { response.status() } returns status
        val call = mockk<ServerCall>()
        every { call.request } returns request
        every { call.response } returns response
        every { call.attributes } returns mutableMapOf()
        every { call.routePattern } returns null
        return call
    }

    @Test
    fun `before call creates server span with request attributes`() = runTest {
        val call = call()
        TracingMiddleware(tracer).beforeCall(call)

        assertTrue(call.attributes.values.single() === span)
        verify { tracer.spanBuilder("GET") }
        verify { builder.setSpanKind(SpanKind.SERVER) }
        verify { builder.setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, "GET") }
        verify { builder.setAttribute(UrlAttributes.URL_PATH, "/items/123") }
    }

    @Test
    fun `after call names matched and unmatched spans and records status`() = runTest {
        val middleware = TracingMiddleware(tracer)
        var call = call(HttpStatusCode.OK)
        middleware.beforeCall(call)
        every { call.routePattern } returns "/items/{id}"
        middleware.afterCall(call)
        verify { span.updateName("GET /items/{id}") }
        verify { span.setAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, 200L) }
        verify { span.end() }

        call = call(HttpStatusCode.InternalServerError)
        middleware.beforeCall(call)
        middleware.afterCall(call)
        verify { span.updateName("GET <unmatched>") }
        verify { span.setAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, 500L) }
        verify { span.setStatus(StatusCode.ERROR) }

        call = call(null)
        middleware.beforeCall(call)
        middleware.afterCall(call)
    }

    @Test
    fun `after call and exception are no-ops without a span`() = runTest {
        val middleware = TracingMiddleware(tracer)
        val call = call()
        middleware.afterCall(call)
        middleware.onException(call, RuntimeException("ignored"))
    }

    @Test
    fun `exception records throwable and safe class-only status`() = runTest {
        val middleware = TracingMiddleware(tracer)
        val call = call()
        middleware.beforeCall(call)
        val failure = IllegalArgumentException("secret detail")
        middleware.onException(call, failure)

        verify { span.recordException(failure) }
        verify { span.setStatus(StatusCode.ERROR, "IllegalArgumentException") }
    }
}

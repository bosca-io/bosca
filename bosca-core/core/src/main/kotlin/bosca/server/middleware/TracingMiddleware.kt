package bosca.server.middleware

import bosca.server.ServerCall
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.semconv.HttpAttributes
import io.opentelemetry.semconv.UrlAttributes

/**
 * CallMiddleware that creates OpenTelemetry spans for incoming HTTP requests, replacing the
 * automatic instrumentation previously provided by the `opentelemetry-ktor` library.
 *
 * Each request gets a server span with standard HTTP semantic convention attributes
 * (method, route, status code). The span is stored in call attributes and ended in
 * [afterCall], ensuring it covers the full request lifecycle including other middleware.
 */
class TracingMiddleware(private val tracer: Tracer) : CallMiddleware {

    override suspend fun beforeCall(call: ServerCall) {
        val method = call.request.httpMethod.value
        val path = call.request.path
        // Use a placeholder span name; afterCall will update it with the route template
        // to avoid high-cardinality span names from concrete paths
        val span = tracer.spanBuilder(method)
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, method)
            .setAttribute(UrlAttributes.URL_PATH, path)
            .startSpan()
        call.attributes[SPAN_ATTRIBUTE_KEY] = span
    }

    override suspend fun afterCall(call: ServerCall) {
        val span = call.attributes[SPAN_ATTRIBUTE_KEY] as? Span ?: return
        // Update span name with route template (low cardinality) instead of concrete path
        val routePattern = call.routePattern
        val method = call.request.httpMethod.value
        // Use route template for matched routes; for unmatched routes use a generic name
        // to prevent high-cardinality span name explosion from arbitrary paths.
        span.updateName(if (routePattern != null) "$method $routePattern" else "$method <unmatched>")
        val status = call.response.status()
        if (status != null) {
            span.setAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, status.value.toLong())
            if (status.value >= 500) {
                span.setStatus(StatusCode.ERROR)
            }
        }
        span.end()
    }

    override suspend fun onException(call: ServerCall, cause: Throwable) {
        val span = call.attributes[SPAN_ATTRIBUTE_KEY] as? Span ?: return
        span.recordException(cause)
        // Use only the exception class name to avoid leaking sensitive information
        // (SQL errors, file paths, credential fragments) into the tracing backend
        span.setStatus(StatusCode.ERROR, cause::class.simpleName ?: "Unknown error")
    }

    companion object {
        private const val SPAN_ATTRIBUTE_KEY = "bosca.tracing.span"
    }
}

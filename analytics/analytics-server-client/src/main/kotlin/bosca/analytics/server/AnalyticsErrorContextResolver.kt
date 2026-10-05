package bosca.analytics.server

import bosca.server.ServerCall

/**
 * Computes the final analytics context map for an error event by
 * folding together every available source in a deterministic
 * precedence order:
 *
 * 1. **Middleware-collected request context** (lowest priority) —
 *    request method, path, route pattern, status, remote address,
 *    principal id, session id, and allowlisted headers extracted from
 *    the [ServerCall] by [AnalyticsMiddleware].
 * 2. **`call.analyticsContext` typed context** — entries written by
 *    handlers and inner middleware before an exception occurs.
 * 3. **[AnalyticsContextElement] coroutine context element** — ambient
 *    context propagated through suspend code, used for non-request
 *    paths (jobs, workers, schedulers).
 * 4. **Explicit `context:` argument** to
 *    [ServerAnalyticsClient.captureException] (highest priority) —
 *    always wins.
 *
 * Later sources override earlier sources on key collision; null values
 * supplied by higher-priority sources do remove the entry. Keys are
 * preserved as-is and not normalized — callers should agree on naming
 * conventions (camelCase is the convention in the rest of the
 * analytics codebase).
 */
object AnalyticsErrorContextResolver {

    /**
     * Resolves the merged context for a request-bound exception.
     */
    fun resolve(
        call: ServerCall?,
        ambient: Map<String, Any?>,
        explicit: Map<String, Any?>,
        requestContext: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(
            requestContext.size + explicit.size + ambient.size + 8,
        )
        out.putAll(requestContext)
        if (call != null) {
            // Read the call identity and its typed overrides at capture time.
            out.putAll(call.analyticsContextMap())
        }
        out.putAll(ambient)
        out.putAll(explicit)
        return out
    }

    /**
     * Resolves the merged context for an exception captured outside a
     * request. Identical to [resolve] without [call] or
     * [requestContext], retained as a separate entry point for
     * readability at call sites.
     */
    fun resolveAmbient(
        ambient: Map<String, Any?>,
        explicit: Map<String, Any?>,
    ): Map<String, Any?> = resolve(call = null, ambient = ambient, explicit = explicit)
}

package bosca.analytics.server

import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.middleware.CallMiddleware
import bosca.server.middleware.HandlerMiddleware
import org.slf4j.LoggerFactory

/**
 * Establishes analytics context during handler execution and forwards unhandled HTTP
 * exceptions to the analytics pipeline as `Error` events.
 *
 * On exception the middleware:
 * 1. Builds an [bosca.analytics.model.ErrorInfo] from the throwable
 *    via [ThrowableErrorInfoMapper] (type, message, stack trace,
 *    fatal flag).
 * 2. Pulls request metadata off the [ServerCall] (method, path, route
 *    pattern, response status, remote address, principal id, session
 *    id, allowlisted headers).
 * 3. Merges per-call analytics typed context ([analyticsContext]) and any
 *    [AnalyticsContextElement] from the coroutine context.
 * 4. Sends the resulting event through [ServerAnalyticsClient.capture]
 *    inside an [AnalyticsCaptureGuard] so a transitive failure inside
 *    the analytics pipeline cannot recurse forever.
 *
 * Errors that originate inside the analytics pipeline itself (or any
 * other code already running under [AnalyticsCaptureGuard]) are
 * dropped with a debug log instead of being captured a second time.
 *
 * The middleware does not modify the response — the existing
 * [bosca.http.StatusPageModule] continues to render error pages and
 * write the HTTP status. This middleware purely observes.
 */
class AnalyticsMiddleware(
    private val client: ServerAnalyticsClient,
    private val appId: String,
    private val headerAllowList: Set<String> = DEFAULT_HEADER_ALLOW_LIST,
    /**
     * When true, error events are marked fatal when the response status
     * is ≥ 500 (or when no status has been written). Set to false to
     * report every middleware capture as non-fatal — useful for
     * environments where the analytics team prefers an explicit fatal
     * signal from the throwing code itself.
     */
    private val markServerErrorsAsFatal: Boolean = true,
) : CallMiddleware, HandlerMiddleware {

    override suspend fun onHandler(call: ServerCall, next: suspend () -> Unit) {
        call.withAnalyticsContext(next)
    }

    /** Normalized (lowercase) copy of the allow list for
     *  case-insensitive header matching. */
    private val normalizedAllowList: Set<String> = headerAllowList.mapTo(LinkedHashSet()) { it.lowercase() }

    init {
        for (header in normalizedAllowList) {
            require(header != "authorization") {
                "Authorization header must never be added to the analytics header allow list"
            }
            require(header != "cookie") {
                "Cookie header must never be added to the analytics header allow list"
            }
            require(header != "proxy-authorization") {
                "Proxy-Authorization header must never be added to the analytics header allow list"
            }
        }
    }

    override suspend fun onException(call: ServerCall, cause: Throwable) {
        // Build context outside the capture try-catch so a bug in
        // context collection is logged alongside the *original*
        // exception rather than silently swallowed.
        val merged: Map<String, Any?> = try {
            val requestContext = collectRequestContext(call)
            val ambient = analyticsContext()
            AnalyticsErrorContextResolver.resolve(
                call = call,
                ambient = ambient.toMap(),
                explicit = emptyMap(),
                requestContext = requestContext,
            )
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            log.error(
                "AnalyticsMiddleware failed to collect context for {}; " +
                    "falling back to minimal context",
                cause::class.simpleName,
                t,
            )
            mapOf(
                "context_collection_failed" to true,
                "original_error_type" to (cause::class.simpleName ?: "Unknown"),
            )
        }

        try {
            log.debug("captured exception {}: {}", cause::class.simpleName, merged)
            // The client itself activates the recursion guard around the
            // pipeline call, so we deliberately do not wrap this in
            // withAnalyticsCaptureGuard — that would cause the very first
            // dispatch to be dropped as if it were a recursive re-entry.
            client.captureException(
                throwable = cause,
                fatal = isFatal(call),
                appId = appId,
                sessionId = merged["session_id"] as? String,
                userId = call.authenticationContext.principal()?.let { it.id.toString() },
                context = merged,
            )
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            // Last-resort guard: never let the analytics layer surface
            // its own failures back into the request lifecycle.
            log.error("AnalyticsMiddleware itself failed handling an exception", t)
        }
    }

    private fun isFatal(call: ServerCall): Boolean {
        if (!markServerErrorsAsFatal) return false
        val status = call.response.status() ?: return true
        return status.value >= HttpStatusCode.InternalServerError.value
    }

    private fun collectRequestContext(call: ServerCall): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(16)
        val request = call.request
        out["http.method"] = request.httpMethod.value
        // Deliberately only capture the path, not the full URI. Query
        // strings frequently contain access tokens, magic link tokens,
        // session ids, and similar secrets that must not land in error
        // events. The route pattern (below) is the stable identifier;
        // the concrete path captures the variable segments.
        out["http.path"] = request.path
        request.remoteAddress?.let { out["http.remote_address"] = it }
        (call.attributes[ROUTING_PATTERN_ATTRIBUTE] as? String)?.let { out["http.route"] = it }
        call.response.status()?.let { out["http.status_code"] = it.value }
        for (header in normalizedAllowList) {
            request.headers[header]?.let { out["http.header.$header"] = it }
        }
        return out
    }

    companion object {
        private val log = LoggerFactory.getLogger(AnalyticsMiddleware::class.java)

        /** Attribute key the router uses for the matched pattern; mirrors NettyHttpHandler. */
        const val ROUTING_PATTERN_ATTRIBUTE: String = "bosca.routing.pattern"

        /** Attribute key for an explicitly-set analytics session id. */
        const val ANALYTICS_SESSION_ID_ATTRIBUTE: String = "bosca.analytics.session_id"

        /**
         * Default set of request headers that are safe to attach to
         * an error event. Cookies, authorization, and proxy
         * credentials are intentionally excluded.
         */
        val DEFAULT_HEADER_ALLOW_LIST: Set<String> = setOf(
            "user-agent",
            "referer",
            "x-request-id",
            "x-forwarded-for",
            "x-forwarded-host",
            "accept",
            "accept-language",
            "content-type",
        )
    }
}

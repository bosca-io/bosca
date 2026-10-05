package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.RequestBodyTooLargeException
import bosca.server.ServerCall
import bosca.server.routing.AuthConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * The middleware lifecycle shared by every protocol the server speaks: ordinary HTTP requests,
 * Server-Sent Events, and WebSocket upgrades over HTTP/1 and HTTP/2.
 *
 * Each protocol path composes these steps around its own transport work:
 *
 * 1. [admit]: `beforeCall` middleware in order, stopping once one commits the response, then
 *    authentication when the route requires it and the response is still open.
 * 2. The protocol's handler.
 * 3. On failure, [fail] (or [unwindException] for transports that answer failures themselves).
 * 4. [finish]: release multipart data, then `afterCall` middleware in reverse order.
 */
internal class CallLifecycle(private val application: BoscaApplication) {

    /** Runs `beforeCall` middleware in installation order, stopping once the response is committed. */
    suspend fun beforeCall(call: ServerCall) {
        for (middleware in application.middleware) {
            if (call.response.isCommitted) return
            middleware.beforeCall(call)
        }
    }

    /** Runs every authentication middleware when [authConfig] is set and the response is still open. */
    suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?) {
        if (authConfig == null || call.response.isCommitted) return
        for (middleware in application.authMiddleware) {
            middleware.authenticate(call, authConfig)
        }
    }

    /**
     * Runs [beforeCall] then [authenticate]. Returns whether the handler may run, which is false
     * once any middleware has committed a response (a CORS preflight, a 401, a redirect, …).
     */
    suspend fun admit(call: ServerCall, authConfig: AuthConfig?): Boolean {
        beforeCall(call)
        authenticate(call, authConfig)
        return !call.response.isCommitted
    }

    /**
     * Answers a failed HTTP call.
     *
     * An oversized request body is the client's error: it becomes `413 Payload Too Large` while
     * the response is still open and is not reported to `onException`. Any other failure unwinds
     * `onException` middleware and falls back to `500 Internal Server Error` if none responded.
     */
    suspend fun fail(call: ServerCall, cause: Exception) {
        if (cause is RequestBodyTooLargeException) {
            log.error("Request body too large for {}: {}", call.request.uri, cause.message, cause)
            call.response.respondText("Payload Too Large", ContentType.Text.Plain, HttpStatusCode.PayloadTooLarge)
            return
        }
        log.error("Call failed for {}", call.request.uri, cause)
        unwindException(call, cause)
        call.response.respondText("Internal Server Error", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
    }

    /**
     * Unwinds `onException` middleware in reverse installation order.
     *
     * - When the response was already committed before the unwind began (a streaming SSE response,
     *   an upgraded WebSocket), no middleware can answer the failure, so every one is notified.
     * - Otherwise the unwind stops at the first middleware that commits an error response.
     *
     * A failing `onException` is logged and captured without interrupting the unwind. When no
     * middleware was invoked, [cause] is captured directly so the failure is never lost.
     */
    suspend fun unwindException(call: ServerCall, cause: Throwable) {
        val committedBeforeUnwind = call.response.isCommitted
        var notified = false
        for (middleware in application.middleware.asReversed()) {
            if (!committedBeforeUnwind && call.response.isCommitted) break
            notified = true
            try {
                middleware.onException(call, cause)
            } catch (e: CancellationException) {
                // Cancellation stops the unwind, but the failure being unwound must not be lost with
                // it: middleware further down (often the one that reports errors) will not run.
                withContext(NonCancellable) { application.errorCapture.capture(cause, call, emptyMap()) }
                throw e
            } catch (e: Exception) {
                log.error("onException middleware failed", e)
                application.errorCapture.capture(e, call, emptyMap())
            }
        }
        if (!notified) {
            application.errorCapture.capture(cause, call, emptyMap())
        }
    }

    /**
     * Releases multipart data and unwinds `afterCall` middleware in reverse installation order.
     *
     * Runs under [NonCancellable]: a disconnected client cancels the channel's scope before
     * cleanup starts, and middleware teardown may suspend while releasing request resources.
     * A failing `afterCall` is logged and captured; cancellation stops the unwind.
     */
    suspend fun finish(call: ServerCall) {
        withContext(NonCancellable) {
            call.cleanup()
            for (middleware in application.middleware.asReversed()) {
                try {
                    middleware.afterCall(call)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("afterCall middleware failed", e)
                    application.errorCapture.capture(e, call, emptyMap())
                }
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(CallLifecycle::class.java)
    }
}

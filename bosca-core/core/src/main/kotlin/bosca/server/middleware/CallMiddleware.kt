package bosca.server.middleware

import bosca.server.ServerCall
import bosca.server.BoscaApplication
import bosca.server.routing.AuthConfig

/**
 * Interface for HTTP middleware that can intercept requests before and after route handling,
 * and handle exceptions that occur during request processing.
 *
 * CallMiddleware instances are registered on the [BoscaApplication] and executed in order for
 * every request.
 */
interface CallMiddleware {
    /** Called before the route handler. Can modify the call or short-circuit by committing a response. */
    suspend fun beforeCall(call: ServerCall) {}

    /**
     * Called immediately before the response is written to the network, after the committed
     * flag is set but before headers and cookies are serialized. This fires from all response
     * paths (commit, respondBytes, respondStreaming) and is the correct place for middleware
     * that needs to modify response headers or cookies (e.g., session cookies).
     *
     * Non-suspend because it is called from non-suspend response methods.
     */
    fun onBeforeWrite(call: ServerCall) {}

    /** Called after the route handler completes, regardless of success or failure. */
    suspend fun afterCall(call: ServerCall) {}

    /** Called when an exception occurs during route handling. Can respond with an error page. */
    suspend fun onException(call: ServerCall, cause: Throwable) {}
}

package bosca.server.middleware

import bosca.server.ServerCall

/** CallMiddleware that wraps route handler execution after authentication. */
fun interface HandlerMiddleware {
    /**
     * Runs [next] in the handler scope. Wrappers nest in registration order, first outermost.
     * Invoke [next] exactly once and propagate failures and cancellation to the call lifecycle.
     */
    suspend fun onHandler(call: ServerCall, next: suspend () -> Unit)
}

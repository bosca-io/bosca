package bosca.server.middleware

import bosca.server.ServerCall

/**
 * Abstraction for persisting session state to the HTTP response.
 *
 * Implementations handle the transport-specific details of writing session data
 * (e.g., setting cookies) and clearing expired or invalidated sessions.
 */
interface SessionWriter {

    /**
     * Persists the given [session] to the response for the specified [call].
     * Called by [SessionMiddleware] when the session has been set during request processing.
     */
    fun writeSession(call: ServerCall, session: Any?)

    /**
     * Removes session data from the response for the specified [call].
     * Called by [SessionMiddleware] when the session has been cleared during request processing.
     */
    fun clearSession(call: ServerCall)
}

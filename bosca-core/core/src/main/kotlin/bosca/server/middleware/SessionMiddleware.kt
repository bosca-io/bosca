package bosca.server.middleware

import bosca.server.ServerCall

/**
 * CallMiddleware that persists session state changes to the HTTP response after
 * route handling completes.
 *
 * When a route handler calls [ServerCall.sessions.set] or [ServerCall.sessions.clear],
 * this middleware detects the modification and delegates to a [SessionWriter] to persist
 * the change (e.g., by writing or clearing a session cookie).
 */
class SessionMiddleware(
    private val sessionWriter: SessionWriter,
) : CallMiddleware {

    override fun onBeforeWrite(call: ServerCall) {
        if (!call.sessions.isModified) return

        val session = call.sessions.raw()
        if (session != null) {
            sessionWriter.writeSession(call, session)
        } else {
            sessionWriter.clearSession(call)
        }
    }
}

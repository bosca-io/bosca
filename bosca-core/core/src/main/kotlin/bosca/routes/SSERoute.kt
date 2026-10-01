package bosca.routes

import bosca.cache.RequestCache
import bosca.cache.asCoroutineContext
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.di.provide
import bosca.security.service.AuthenticationContext
import bosca.server.sse.ServerSSESession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

abstract class SSERoute<T> {

    suspend fun execute(session: ServerSSESession) {
        val connectionPool = provide<ConnectionPool>()
        val cache = RequestCache(provide(), provide())
        val connection = connectionPool.connection()
        try {
            withContext(connection.asCoroutineContext() + cache.asCoroutineContext()) {
                try {
                    execute(session, AuthenticationContext(session.call.authenticationContext, provide()))
                } catch (e: CancellationException) {
                    // Cancellation must propagate — see Route.execute. JVM
                    // `CancellationException` is an `IllegalStateException`, so
                    // the guard keeps a torn-down SSE stream from being logged
                    // as "Invalid request" and swallowed.
                    throw e
                } catch (e: Exception) {
                    val error = e.toAPIError()
                    val message = error.message ?: error.status.description
                    log.error("SSE route request failed with status ${error.status.value}", error.exception)
                    if (!session.call.response.isCommitted) {
                        session.call.respond(error.status, message)
                    } else {
                        session.send(data = message, event = "error")
                        session.close()
                    }
                }
            }
        } finally {
            connection.release()
        }
    }

    protected abstract suspend fun execute(session: ServerSSESession, authenticationContext: AuthenticationContext): T?

    companion object {

        private val log = LoggerFactory.getLogger(SSERoute::class.java)
    }
}

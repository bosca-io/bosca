package bosca.routes

import bosca.cache.RequestCache
import bosca.cache.asCoroutineContext
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.di.provide
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

abstract class Route<T> {

    /**
     * Returns the serializer for the route's response type. Used to serialize the value
     * returned by [execute] as JSON in the HTTP response.
     *
     * Return null for types that are dispatched directly by [ServerCall.respond] without
     * JSON serialization (Unit, String, ByteArray, HttpStatusCode, ServerResponseContent).
     * All other types must provide a non-null serializer.
     */
    protected open fun serializer(): KSerializer<T>? = null

    suspend fun execute(call: ServerCall) {
        val connectionPool = provide<ConnectionPool>()
        val cache = RequestCache(provide(), provide())
        val connection = connectionPool.connection()
        val model = try {
            withContext(connection.asCoroutineContext() + cache.asCoroutineContext()) {
                try {
                    execute(call, AuthenticationContext(call.authenticationContext, provide()))
                } catch (e: CancellationException) {
                    // Coroutine cancellation (client disconnect, the streaming
                    // timeout cap, parent scope cancel) must propagate untouched.
                    // On the JVM `CancellationException` extends
                    // `IllegalStateException`, so without this guard the catch
                    // below swallows every cancelled stream and mislabels it
                    // "Invalid request" — breaking structured concurrency and
                    // spamming the log on each normal teardown.
                    throw e
                } catch (e: Exception) {
                    val error = e.toAPIError()
                    log.error("Route request failed with status ${error.status.value}", error.exception)
                    if (!call.response.isCommitted) {
                        call.respond(error.status, error.message ?: error.status.description)
                    }
                }
            }
        } finally {
            connection.release()
        }
        if (call.response.isCommitted) return
        if (model is HttpStatusCode) {
            call.respond(model, "")
            return
        }
        if (call.response.status() == null && model == null) {
            call.respond(HttpStatusCode.NotFound, "")
            return
        }
        if (model != null && model != Unit) {
            @Suppress("UNCHECKED_CAST")
            call.respond(model as T, serializer())
        } else if (call.response.status() == null) {
            call.respond(HttpStatusCode.NoContent, "")
        } else if (!call.response.isCommitted) {
            call.respond(call.response.status() ?: error("no status"), "")
        }
    }

    protected abstract suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): T?

    companion object {

        private val log = LoggerFactory.getLogger(Route::class.java)
    }
}

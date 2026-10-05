package bosca.routes

import bosca.cache.RequestCache
import bosca.cache.asCoroutineContext
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.di.provide
import bosca.graphql.server.GraphQLException
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.ServerResponseContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Error response returned by [APIRoute] when an exception occurs.
 *
 * The original [exception] is retained for logging and inspection while the HTTP
 * response contains [status] and [message]. Client-error messages are exposed;
 * server-error details remain available only through logs and error capture.
 */
data class APIError(
    val status: HttpStatusCode,
    val exception: Throwable,
) : ServerResponseContent {

    /** The safe client-facing message, or null when exception details must remain private. */
    val message: String?
        get() = exception.message.takeIf { status.value in 400..499 }

    override suspend fun writeTo(call: ServerCall, status: HttpStatusCode) {
        call.respond(
            this.status,
            APIErrorPayload(this.status.value, message),
        )
    }
}

@Serializable
private data class APIErrorPayload(
    val status: Int,
    val message: String?,
)

/**
 * Route base class for JSON API endpoints that returns structured error responses.
 *
 * Unlike [Route], which responds with plain text on errors, [APIRoute] returns
 * [APIError] JSON objects with appropriate HTTP status codes. This provides API
 * clients with machine-readable error information.
 *
 * Exception mapping:
 * - [SecurityException] → 401 Unauthorized
 * - [GraphQLException], [IllegalArgumentException], or [IllegalStateException] → 400 Bad Request
 * - [NoSuchElementException] → 404 Not Found
 * - Other exceptions → 500 Internal Server Error
 *
 * Every error response includes the exception message so API clients receive the
 * actionable reason for the failure rather than only the HTTP status description.
 */
abstract class APIRoute<T> {

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
                    // Cancellation must propagate — see Route.execute. JVM
                    // `CancellationException` is an `IllegalStateException`, so
                    // the guard keeps a cancelled request from being logged as
                    // "Invalid request" and converted to a 400 APIError.
                    throw e
                } catch (e: Exception) {
                    val error = e.toAPIError()
                    log.error("API request failed with status ${error.status.value}", error.exception)
                    call.respond(error.status, error)
                }
            }
        } finally {
            connection.release()
        }
        if (call.response.status() == null && model == null) {
            val error = APIError(HttpStatusCode.NotFound, NoSuchElementException("Not Found"))
            call.respond(error.status, error)
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

    /**
     * Handles the request and returns the response model.
     *
     * Exceptions thrown from this method are caught and converted to structured
     * [APIError] JSON responses with appropriate HTTP status codes.
     */
    protected abstract suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): T?

    companion object {

        private val log = LoggerFactory.getLogger(APIRoute::class.java)
    }
}

/** Maps a route exception to its HTTP status while retaining the original exception. */
internal fun Throwable.toAPIError(): APIError {
    val status = when (this) {
        is SecurityException -> HttpStatusCode.Unauthorized
        is GraphQLException,
        is IllegalArgumentException,
        is IllegalStateException -> HttpStatusCode.BadRequest
        is NoSuchElementException -> HttpStatusCode.NotFound
        else -> HttpStatusCode.InternalServerError
    }
    return APIError(status, this)
}

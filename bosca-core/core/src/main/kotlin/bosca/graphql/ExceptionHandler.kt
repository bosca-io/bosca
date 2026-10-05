package bosca.graphql

import bosca.graphql.language.SourceLocation
import bosca.graphql.server.DataFetcherExceptionHandler
import bosca.graphql.server.GraphQLContext
import bosca.graphql.server.GraphQLError
import bosca.graphql.server.GraphQLException
import bosca.observability.ErrorCapture
import bosca.routes.toAPIError
import bosca.server.ServerCall
import bosca.telemetry.Tracing
import java.util.concurrent.CompletionException
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Maps client-caused GraphQL failures to their safe message and HTTP-equivalent status while sanitizing
 * unexpected server failures. Every error includes an opaque trace identifier that correlates the client
 * response with the full exception in logs and analytics.
 */
class ExceptionHandler(
    private val errorCaptureProvider: (ServerCall?) -> ErrorCapture? = { call -> call?.application?.errorCapture },
) : DataFetcherExceptionHandler {

    /** Handles an execution-level failure outside an individual resolver field. */
    suspend fun handleException(exception: Throwable): GraphQLError =
        handle(exception, emptyList(), null, GraphQLContext.EMPTY)

    override suspend fun handle(
        exception: Throwable,
        path: List<Any>,
        location: SourceLocation?,
        context: GraphQLContext,
    ): GraphQLError {
        val traceId = Tracing.currentTraceId() ?: Tracing.newTraceId()
        val call = context.getAs<ServerCall>("call")
        errorCaptureProvider(call)?.capture(exception, call, mapOf(Tracing.TRACE_ID_KEY to traceId))
        log.error("GraphQL error traceId={}", traceId, exception)
        return error(exception, path, location, traceId)
    }

    private fun error(exception: Throwable, path: List<Any>, location: SourceLocation?, traceId: String): GraphQLError {
        val cause = unwrap(exception)
        val safe = cause as? GraphQLException
        val apiError = cause.toAPIError()
        val extensions = buildMap {
            safe?.extensions?.let { putAll(it) }
                ?: codeOf(cause)?.let { put("code", JsonPrimitive(it)) }
            // Always overwrite caller-supplied transport metadata so it reflects the mapped exception.
            put("status", JsonPrimitive(apiError.status.value))
            // Always overwrite a caller-supplied value so client code cannot spoof an operational correlation ID.
            put(Tracing.TRACE_ID_KEY, JsonPrimitive(traceId))
        }
        return GraphQLError(
            message = apiError.message ?: "Internal server error",
            locations = location?.let(::listOf).orEmpty(),
            path = path,
            extensions = extensions,
        )
    }

    /** Walks the cause chain for a [CodedError], returning its machine-readable code for `extensions.code`. */
    private fun codeOf(exception: Throwable): String? = exception.codedErrorCode()

    private fun unwrap(exception: Throwable): Throwable =
        if (exception is CompletionException) exception.cause ?: exception else exception

    private val log = LoggerFactory.getLogger(ExceptionHandler::class.java)
}

package bosca.graphql.server

import bosca.graphql.language.SourceLocation
import kotlinx.serialization.json.JsonElement

/**
 * Maps an exception thrown while resolving a field to a [GraphQLError] (graphql-java's
 * `DataFetcherExceptionHandler`). The engine calls this with the field's response [path], source [location], and
 * request [context].
 */
fun interface DataFetcherExceptionHandler {
    /** Converts [exception] into a client-facing error while preserving coroutine context across suspending work. */
    suspend fun handle(
        exception: Throwable,
        path: List<Any>,
        location: SourceLocation?,
        context: GraphQLContext,
    ): GraphQLError
}

/**
 * The secure default handler: explicitly client-safe [GraphQLException] details pass through, while unexpected
 * resolver failures receive a generic message. The field's path + location are always attached.
 */
object DefaultDataFetcherExceptionHandler : DataFetcherExceptionHandler {
    override suspend fun handle(
        exception: Throwable,
        path: List<Any>,
        location: SourceLocation?,
        context: GraphQLContext,
    ): GraphQLError {
        val safe = exception as? GraphQLException
        return GraphQLError(
            message = safe?.message ?: "Internal server error",
            locations = if (location != null) listOf(location) else emptyList(),
            path = path,
            extensions = safe?.extensions,
        )
    }
}

/** A resolver exception that carries a clean message + optional [extensions] to surface in the error. */
open class GraphQLException(message: String, val extensions: Map<String, JsonElement>? = null) : RuntimeException(message)

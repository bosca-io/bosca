package bosca.bml.graphql

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A GraphQL request envelope. */
@Serializable
data class GraphQLRequest(
    val query: String,
    val variables: JsonObject? = null,
    val operationName: String? = null,
)

/** A GraphQL error entry. */
@Serializable
data class GraphQLError(val message: String)

/** A GraphQL response envelope. */
@Serializable
data class GraphQLResponse(
    val data: JsonElement? = null,
    val errors: List<GraphQLError>? = null,
)

/** Raised when a GraphQL request fails (transport or `errors` payload). */
class GraphQLException(message: String) : RuntimeException(message)

/**
 * The single data plane for BML (server / client). Executes
 * GraphQL against the configured Bosca endpoint; the [token] is forwarded
 * (passthrough) — the server holds no auth library. Typed operation wrappers are
 * generated on top of this (`bosca.query(GetListView(id))`).
 */
interface GraphQLClient {
    suspend fun execute(
        query: String,
        variables: JsonObject? = null,
        operationName: String? = null,
        token: String? = null,
    ): JsonElement
}

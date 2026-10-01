package bosca.graphql.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The shared `Json` the generated codecs use. Lenient about extra response fields (e.g. `__typename`).
 *
 * `explicitNulls = false` is essential for GraphQL inputs: a null-valued (absent) variable / input field is
 * **omitted** rather than sent as an explicit `null`, matching GraphQL "absent ⇒ use the server default" semantics
 * (the role Apollo's `Optional.Absent` played). Sending explicit `null` makes a server reject a field that has a
 * default or a non-null backing type (e.g. `customFields`). `encodeDefaults = true` still emits rendered non-null
 * defaults; response decoding is unaffected (an explicit `null` in a response still decodes to null).
 */
val GraphQLJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** A GraphQL error entry from the response envelope. */
@Serializable
data class GraphQLError(val message: String)

/** The GraphQL response envelope: `{ data, errors }`. */
@Serializable
data class GraphQLResponse(val data: JsonElement? = null, val errors: List<GraphQLError>? = null)

/** Raised when a GraphQL response carries an `errors` array (or no usable `data`). */
class GraphQLClientException(val errors: List<GraphQLError>) :
    RuntimeException("GraphQL errors: " + errors.joinToString("; ") { it.message })

/**
 * The transport the typed client rides — Bosca's existing token-passthrough clients adapt to this; there
 * is **no** Apollo runtime, normalized cache, or websocket layer here. An implementation sends the
 * [document] + [variables] to the configured endpoint and returns the parsed [GraphQLResponse].
 */
interface GraphQLClient {
    suspend fun execute(document: String, variables: JsonObject?, operationName: String?): GraphQLResponse
}

/**
 * Run a generated [operation] with typed [variables] and return its typed `Data`. Throws
 * [GraphQLClientException] if the response carries errors or has no `data`.
 */
suspend fun <V, D> GraphQLClient.execute(operation: BoscaOperation<V, D>, variables: V): D {
    val response = execute(operation.document, operation.encodeVariables(variables), operation.operationName)
    response.errors?.takeIf { it.isNotEmpty() }?.let { throw GraphQLClientException(it) }
    val data = response.data ?: throw GraphQLClientException(listOf(GraphQLError("response contained no data")))
    return operation.decodeData(data)
}

package bosca.graphql.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The `graphql-transport-ws` wire protocol (the subprotocol Bosca's server speaks), as transport-agnostic
 * message builders + a parser. The actual socket (OkHttp / JDK) is supplied by each consumer; this keeps the
 * protocol logic pure and testable. See [GraphQLSubscriptionClient].
 */
object WsProtocol {
    /** The `Sec-WebSocket-Protocol` value to negotiate. */
    const val SUBPROTOCOL: String = "graphql-transport-ws"

    /** `connection_init` — the first client message; [payload] typically carries auth (e.g. a bearer token). */
    fun connectionInit(payload: JsonObject? = null): String = buildJsonObject {
        put("type", JsonPrimitive("connection_init"))
        if (payload != null) put("payload", payload)
    }.toString()

    /** `subscribe` — start operation [id] with the given document/variables. */
    fun subscribe(id: String, document: String, variables: JsonObject?, operationName: String?): String = buildJsonObject {
        put("type", JsonPrimitive("subscribe"))
        put("id", JsonPrimitive(id))
        put(
            "payload",
            buildJsonObject {
                put("query", JsonPrimitive(document))
                if (operationName != null) put("operationName", JsonPrimitive(operationName))
                if (variables != null) put("variables", variables)
            },
        )
    }.toString()

    /** `complete` — client-initiated stop of operation [id]. */
    fun complete(id: String): String = buildJsonObject {
        put("type", JsonPrimitive("complete"))
        put("id", JsonPrimitive(id))
    }.toString()

    /** `pong` — reply to a server `ping` (keep-alive). */
    fun pong(): String = """{"type":"pong"}"""

    /** Parse a server→client message into a typed [WsServerMessage]. */
    fun parse(text: String): WsServerMessage {
        val obj = GraphQLJson.parseToJsonElement(text).jsonObject
        return when (val type = obj["type"]?.jsonPrimitive?.content) {
            "connection_ack" -> WsServerMessage.ConnectionAck
            "next" -> WsServerMessage.Next(
                obj.getValue("id").jsonPrimitive.content,
                GraphQLJson.decodeFromJsonElement(GraphQLResponse.serializer(), obj.getValue("payload")),
            )
            "error" -> WsServerMessage.Error(
                obj.getValue("id").jsonPrimitive.content,
                GraphQLJson.decodeFromJsonElement(ListSerializer(GraphQLError.serializer()), obj.getValue("payload")),
            )
            "complete" -> WsServerMessage.Complete(obj.getValue("id").jsonPrimitive.content)
            "ping" -> WsServerMessage.Ping
            "pong" -> WsServerMessage.Pong
            else -> WsServerMessage.Unknown(type ?: "")
        }
    }
}

/** A typed server→client `graphql-transport-ws` message. */
sealed interface WsServerMessage {
    /** Server accepted `connection_init`; the client may now `subscribe`. */
    data object ConnectionAck : WsServerMessage

    /** A subscription payload for operation [id]. */
    data class Next(val id: String, val response: GraphQLResponse) : WsServerMessage

    /** A subscription error for operation [id] (terminates the operation). */
    data class Error(val id: String, val errors: List<GraphQLError>) : WsServerMessage

    /** The server completed operation [id]. */
    data class Complete(val id: String) : WsServerMessage

    /** Keep-alive ping; reply with [WsProtocol.pong]. */
    data object Ping : WsServerMessage

    /** Keep-alive pong (server reply to a client ping). */
    data object Pong : WsServerMessage

    /** Any other/unrecognized message [type]. */
    data class Unknown(val type: String) : WsServerMessage
}

/**
 * A transport that runs GraphQL **subscriptions**, returning a cold [Flow] of response frames. Implemented over
 * a real WebSocket by each consumer (e.g. the CLI's `BoscaWebSocketClient`); the [WsProtocol] handles the wire
 * format. Kept separate from [GraphQLClient] so a transport opts in only if it can stream.
 */
interface GraphQLSubscriptionClient {
    /** Subscribe to [document] with [variables]; each server `next` frame becomes one emitted [GraphQLResponse]. */
    fun subscribe(document: String, variables: JsonObject?, operationName: String?): Flow<GraphQLResponse>
}

/**
 * Run a generated subscription [operation] with typed [variables], emitting decoded `Data` per frame. A frame
 * carrying errors (or no data) throws [GraphQLClientException] into the flow.
 */
fun <V, D> GraphQLSubscriptionClient.subscribe(operation: BoscaOperation<V, D>, variables: V): Flow<D> =
    subscribe(operation.document, operation.encodeVariables(variables), operation.operationName).map { response ->
        response.errors?.takeIf { it.isNotEmpty() }?.let { throw GraphQLClientException(it) }
        val data = response.data ?: throw GraphQLClientException(listOf(GraphQLError("response contained no data")))
        operation.decodeData(data)
    }

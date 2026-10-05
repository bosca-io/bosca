package bosca.cli.api

import bosca.graphql.client.GraphQLClientException
import bosca.graphql.client.GraphQLResponse
import bosca.graphql.client.GraphQLSubscriptionClient
import bosca.graphql.client.WsProtocol
import bosca.graphql.client.WsServerMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The CLI's GraphQL **subscription** transport — the Apollo WebSocket replacement. Speaks `graphql-transport-ws`
 * (via [WsProtocol]) over an OkHttp WebSocket on [NetworkClient.http]. Each subscription is an independent cold
 * [Flow]; collecting it opens the socket, and cancelling it sends `complete` and closes.
 *
 * Auth mirrors the old Apollo WS engine: the bearer token is attached both as an `Authorization` header on the
 * upgrade request and in the `connection_init` payload, so whichever the server reads is satisfied.
 */
class BoscaWebSocketClient(
    private val wsUrl: String,
    private val http: OkHttpClient,
    private val token: suspend () -> String?,
) : GraphQLSubscriptionClient {

    override fun subscribe(document: String, variables: JsonObject?, operationName: String?): Flow<GraphQLResponse> = callbackFlow {
        val bearer = token()
        val request = Request.Builder()
            .url(wsUrl)
            .header("Sec-WebSocket-Protocol", WsProtocol.SUBPROTOCOL)
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()

        val operationId = "1"
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val initPayload = bearer?.let { buildJsonObject { put("Authorization", JsonPrimitive("Bearer $it")) } }
                webSocket.send(WsProtocol.connectionInit(initPayload))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val message = WsProtocol.parse(text)) {
                    is WsServerMessage.ConnectionAck ->
                        webSocket.send(WsProtocol.subscribe(operationId, document, variables, operationName))
                    is WsServerMessage.Next -> trySend(message.response)
                    is WsServerMessage.Error -> close(GraphQLClientException(message.errors))
                    is WsServerMessage.Complete -> close()
                    is WsServerMessage.Ping -> webSocket.send(WsProtocol.pong())
                    is WsServerMessage.Pong -> Unit
                    is WsServerMessage.Unknown -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }

        val webSocket = http.newWebSocket(request, listener)
        awaitClose {
            webSocket.send(WsProtocol.complete(operationId))
            webSocket.close(1000, null)
        }
    }
}

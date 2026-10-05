package bosca.graphql.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/**
 * A multiplatform [GraphQLSubscriptionClient] over Ktor websockets, speaking the `graphql-transport-ws`
 * subprotocol ([WsProtocol]) — the streaming counterpart to [KtorGraphQLClient], for the targets the JVM-only
 * CLI socket doesn't reach (Android, iOS, JS, Wasm, Desktop).
 *
 * Each [subscribe] opens its own socket (simple; no multiplexing across subscriptions), sends `connection_init`
 * carrying the [connectionPayloadProvider] payload (typically a bearer token — the role Apollo's
 * `connectionPayload` played), waits for `connection_ack`, then `subscribe`s and emits every server `next`
 * frame as a [GraphQLResponse]. It replies to server `ping`s with `pong`, terminates the flow with a
 * [GraphQLClientException] on a subscription `error`, and finishes on `complete`. The [httpClient] must have
 * Ktor's WebSockets plugin installed; the engine (and therefore platform) is the caller's choice.
 */
class KtorGraphQLSubscriptionClient(
    private val endpoint: String,
    private val httpClient: HttpClient,
    private val connectionPayloadProvider: suspend () -> JsonObject? = { null },
) : GraphQLSubscriptionClient {

    override fun subscribe(
        document: String,
        variables: JsonObject?,
        operationName: String?,
    ): Flow<GraphQLResponse> = flow {
        val payload = connectionPayloadProvider()
        httpClient.webSocket(
            urlString = endpoint,
            request = { header(HttpHeaders.SecWebSocketProtocol, WsProtocol.SUBPROTOCOL) },
        ) {
            send(Frame.Text(WsProtocol.connectionInit(payload)))
            val id = "1"
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                when (val message = WsProtocol.parse(text)) {
                    WsServerMessage.ConnectionAck ->
                        send(Frame.Text(WsProtocol.subscribe(id, document, variables, operationName)))
                    is WsServerMessage.Next -> if (message.id == id) emit(message.response)
                    is WsServerMessage.Error -> if (message.id == id) throw GraphQLClientException(message.errors)
                    is WsServerMessage.Complete -> if (message.id == id) return@webSocket
                    WsServerMessage.Ping -> send(Frame.Text(WsProtocol.pong()))
                    WsServerMessage.Pong, is WsServerMessage.Unknown -> {}
                }
            }
        }
    }
}

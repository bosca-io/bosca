package bosca.ai.kit.harness.acp

import com.agentclientprotocol.transport.Transport
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import java.net.URI

/** Endpoint and token originate from runtime discovery and workload authentication, never chat input. */
class AcpWebSocketTransportFactory(
    private val client: HttpClient,
    private val endpoint: URI,
    private val generation: Long? = null,
    private val epoch: Long? = null,
    private val instanceId: String? = null,
    private val bearerToken: suspend () -> String,
) : AcpTransportFactory {
    init {
        require(endpoint.scheme in setOf("ws", "wss") && endpoint.host != null) { "Invalid ACP WebSocket endpoint" }
        require(endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null) {
            "ACP endpoint must not contain credentials, query parameters, or a fragment"
        }
        require(endpoint.path == "/acp") { "ACP endpoint must use /acp" }
    }

    override suspend fun connect(scope: CoroutineScope): Transport {
        val token = bearerToken()
        require(token.isNotBlank() && token.none { it.isWhitespace() }) { "Invalid ACP bearer token" }
        val socket = client.webSocketSession(endpoint.toString()) {
            header(HttpHeaders.Authorization, "Bearer $token")
            generation?.let { header("X-Bosca-Generation", it) }
            epoch?.let { header("X-Bosca-Epoch", it) }
            instanceId?.let { header("X-Bosca-Instance", it) }
        }
        return AcpWebSocketTransport(scope, socket)
    }
}

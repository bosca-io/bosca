package bosca.cli.harness.egress

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.UnknownHostException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A loopback forwarder that lets the harness and its processes use an unauthenticated
 * `http://127.0.0.1:<port>` proxy. Each `CONNECT` is forwarded to the egress [proxy] with the
 * runtime's [credentials]; client-supplied proxy credentials are dropped, and the upstream status
 * and headers are returned to the client before bytes are relayed.
 */
internal class EgressForwarder(
    private val proxy: EgressDestination,
    private val credentials: EgressCredentials,
    private val resolver: EgressResolver = EgressResolver.SYSTEM,
    private val headTimeout: Duration = 10.seconds,
    private val connectTimeout: Duration = 10.seconds,
) {
    /** Binds an ephemeral loopback port and serves until the returned listener is closed or [scope] is cancelled. */
    suspend fun start(scope: CoroutineScope): ProxyListener = ProxyListener.start(scope, "Egress forwarder", LOOPBACK, 0, ::handle)

    private suspend fun handle(socket: Socket, selector: SelectorManager) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel()
        val head = try {
            withTimeoutOrNull(headTimeout) { readProxyHead(input) } ?: return output.writeProxyStatus(408)
        } catch (rejected: ProxyHeadException) { return output.writeProxyStatus(rejected.status) }
        val request = try { ProxyRequestLine.parse(head.startLine) } catch (rejected: ProxyHeadException) { return output.writeProxyStatus(rejected.status) }
        if (request.method != "CONNECT") return output.writeProxyStatus(405, listOf("Allow" to "CONNECT"))
        val destination = EgressDestination.parseOrNull(request.target) ?: return output.writeProxyStatus(400)
        val addresses = try { withTimeoutOrNull(connectTimeout) { resolver.resolve(proxy.host) } } catch (_: UnknownHostException) { null }
            ?: return output.writeProxyStatus(502)
        val upstream = connectFirst(selector, addresses, proxy.port, connectTimeout) ?: return output.writeProxyStatus(502)
        upstream.use {
            val upstreamInput = upstream.openReadChannel()
            val upstreamOutput = upstream.openWriteChannel()
            upstreamOutput.writeFully(connectHead(destination, head).toByteArray(Charsets.ISO_8859_1))
            upstreamOutput.flush()
            val response = try {
                withTimeoutOrNull(headTimeout) { readProxyHead(upstreamInput) } ?: return output.writeProxyStatus(504)
            } catch (_: ProxyHeadException) { return output.writeProxyStatus(502) }
            val status = statusOf(response.startLine) ?: return output.writeProxyStatus(502)
            output.writeFully(serialize(response).toByteArray(Charsets.ISO_8859_1))
            output.forward(response.remainder)
            output.flush()
            if (status != 200) return
            upstreamOutput.forward(head.remainder)
            relayTunnel(input, output, upstreamInput, upstreamOutput)
        }
    }

    private fun connectHead(destination: EgressDestination, head: ProxyHead) = buildString {
        append("CONNECT ").append(destination).append(" HTTP/1.1\r\n")
        append("Host: ").append(destination).append("\r\n")
        head.headers.filterNot { (name, _) -> name.equals("Proxy-Authorization", ignoreCase = true) || name.equals("Host", ignoreCase = true) }
            .forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("Proxy-Authorization: ").append(credentials.proxyAuthorization()).append("\r\n\r\n")
    }

    private fun serialize(head: ProxyHead) = buildString {
        append(head.startLine).append("\r\n")
        head.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("\r\n")
    }

    private fun statusOf(line: String): Int? {
        val parts = line.split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].matches(Regex("HTTP/1\\.[01]")) || !parts[1].matches(Regex("[1-5][0-9]{2}"))) return null
        return parts[1].toInt()
    }

    companion object {
        /** The forwarder only listens on IPv4 loopback. */
        const val LOOPBACK = "127.0.0.1"
    }
}

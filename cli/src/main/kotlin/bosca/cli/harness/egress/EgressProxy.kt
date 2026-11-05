package bosca.cli.harness.egress

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Kit runtime egress proxy. It accepts only authenticated `CONNECT host:port` requests and
 * tunnels to deployment-trusted [allowed] destinations, or to destinations Bosca approved for the
 * runtime's session whose every resolved address is public.
 */
internal class EgressProxy(
    private val lookup: EgressLookup,
    private val allowed: Set<EgressDestination>,
    private val resolver: EgressResolver = EgressResolver.SYSTEM,
    private val isPublic: (InetAddress) -> Boolean = EgressAddresses::isPublic,
    private val headTimeout: Duration = 10.seconds,
    private val connectTimeout: Duration = 10.seconds,
) {
    /** Binds [host]:[port] and serves until the returned listener is closed or [scope] is cancelled. */
    suspend fun start(scope: CoroutineScope, host: String, port: Int): ProxyListener =
        ProxyListener.start(scope, "Egress proxy", host, port, ::handle)

    private suspend fun handle(socket: Socket, selector: SelectorManager) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel()
        val head = try {
            withTimeoutOrNull(headTimeout) { readProxyHead(input) } ?: return output.writeProxyStatus(408)
        } catch (rejected: ProxyHeadException) { return output.writeProxyStatus(rejected.status) }
        val request = try { ProxyRequestLine.parse(head.startLine) } catch (rejected: ProxyHeadException) { return output.writeProxyStatus(rejected.status) }
        if (request.method != "CONNECT") return output.writeProxyStatus(405, listOf("Allow" to "CONNECT"))
        val credentials = parseProxyAuthorization(head.header("Proxy-Authorization"))
            ?: return output.writeProxyStatus(407, listOf("Proxy-Authenticate" to "Basic realm=\"bosca-kit\""))
        val destination = EgressDestination.parseOrNull(request.target) ?: return output.writeProxyStatus(400)
        val trusted = destination in allowed
        if (!trusted) {
            when (val result = lookup.lookup(credentials)) {
                EgressLookupResult.Denied -> return output.writeProxyStatus(403)
                EgressLookupResult.Unavailable -> return output.writeProxyStatus(502)
                is EgressLookupResult.Approved -> if (destination !in result.destinations) return output.writeProxyStatus(403)
            }
        }
        val addresses = resolve(destination.host) ?: return output.writeProxyStatus(502)
        if (addresses.isEmpty() || !trusted && !addresses.all(isPublic)) return output.writeProxyStatus(403)
        val upstream = connectFirst(selector, addresses, destination.port, connectTimeout) ?: return output.writeProxyStatus(502)
        upstream.use {
            val upstreamOutput = upstream.openWriteChannel()
            output.writeProxyStatus(200)
            upstreamOutput.forward(head.remainder)
            relayTunnel(input, output, upstream.openReadChannel(), upstreamOutput)
        }
    }

    private suspend fun resolve(host: String): List<InetAddress>? =
        try { withTimeoutOrNull(connectTimeout) { resolver.resolve(host) } } catch (_: UnknownHostException) { null }
}

/** Writes bytes a peer sent ahead of the tunnel being established. */
internal suspend fun ByteWriteChannel.forward(bytes: ByteArray) {
    if (bytes.isNotEmpty()) { writeFully(bytes); flush() }
}

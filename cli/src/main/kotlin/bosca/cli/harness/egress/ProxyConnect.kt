package bosca.cli.harness.egress

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import kotlin.time.Duration

/**
 * Connects to the first reachable address in [addresses], each attempt bounded by [timeout].
 * Addresses are used as given; nothing is resolved again. Returns null when none connects.
 */
internal suspend fun connectFirst(selector: SelectorManager, addresses: List<InetAddress>, port: Int, timeout: Duration): Socket? {
    for (address in addresses) {
        val socket = try {
            withTimeoutOrNull(timeout) { aSocket(selector).tcp().connect(InetSocketAddress(address.address, port)) }
        } catch (_: IOException) { null }
        if (socket != null) return socket
    }
    return null
}

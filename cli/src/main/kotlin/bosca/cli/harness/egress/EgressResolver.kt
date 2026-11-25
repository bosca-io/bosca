package bosca.cli.harness.egress

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress

/** Resolves a destination host to every address the proxy might connect to. */
internal fun interface EgressResolver {
    /**
     * Returns all addresses for [host].
     *
     * @throws java.net.UnknownHostException when the host does not resolve.
     */
    suspend fun resolve(host: String): List<InetAddress>

    companion object {
        /** The platform resolver, run off the caller's thread. */
        val SYSTEM = EgressResolver { host -> runInterruptible(Dispatchers.IO) { InetAddress.getAllByName(host).toList() } }
    }
}

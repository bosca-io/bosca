package bosca.cli.harness

import bosca.cli.harness.egress.EgressCredentials
import bosca.cli.harness.egress.EgressDestination
import bosca.cli.harness.egress.EgressForwarder
import bosca.cli.harness.egress.parseEgressProxyOrigin
import java.net.URI

/**
 * Proxy settings for a runtime whose internet access goes through the egress proxy: processes use
 * the supervisor's loopback forwarder on [port] and reach [noProxy] hosts directly.
 */
internal data class HarnessEgress(val port: Int, val noProxy: List<String>) {
    /** The unauthenticated proxy URL of the loopback forwarder. */
    val proxyUrl: String get() = "http://${EgressForwarder.LOOPBACK}:$port"

    /** Proxy environment for the harness and every process it starts. */
    fun environment(): Map<String, String> {
        val bypass = noProxy.joinToString(",")
        return PROXY_KEYS.associateWith { proxyUrl } + mapOf("NO_PROXY" to bypass, "no_proxy" to bypass, "NODE_USE_ENV_PROXY" to "1")
    }

    /** The proxy URL for a connection to [url], or null when its host is reached directly. */
    fun proxyFor(url: String): String? {
        val host = URI(url).host?.lowercase()?.removeSuffix(".")?.removePrefix("[")?.removeSuffix("]") ?: return null
        return if (noProxy.any { it.equals(host, ignoreCase = true) }) null else proxyUrl
    }

    companion object {
        private val PROXY_KEYS = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")

        /** Hosts that bypass the proxy: Bosca's own [endpoint] host and loopback. */
        fun noProxy(endpoint: String?): List<String> =
            (listOfNotNull(endpoint?.let { URI(it).host?.removePrefix("[")?.removeSuffix("]")?.lowercase() }) + listOf("localhost", "127.0.0.1", "::1")).distinct()

        /**
         * Returns the egress proxy origin and runtime credentials when both `BOSCA_EGRESS_PROXY` and
         * `BOSCA_EGRESS_TOKEN` are present, or null when the runtime has no egress proxy.
         *
         * @throws IllegalArgumentException when `BOSCA_EGRESS_PROXY` is not an `http://host:port` origin.
         */
        fun settings(environment: Map<String, String>): Pair<EgressDestination, EgressCredentials>? {
            val proxy = environment["BOSCA_EGRESS_PROXY"] ?: return null
            val token = environment["BOSCA_EGRESS_TOKEN"] ?: return null
            return parseEgressProxyOrigin(proxy) to EgressCredentials(environment.getValue("BOSCA_CHAT_SESSION_ID"), token)
        }
    }
}

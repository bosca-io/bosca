package bosca.cli.config

import java.net.URI

/**
 * Hosts of a Bosca deployment's companion services, derived from the deployment's GraphQL
 * endpoint with the layout the Helm charts and Swarm configuration share: the API is served from
 * the base domain or its `api`, `studio`, `www`, `ws`, or `upload` subdomain, and the services
 * live at `artifacts.<domain>` and `git.<domain>`. A local endpoint maps to the development ports.
 */
object DeploymentHosts {

    /** Local development port of the Artifacts server. */
    const val LOCAL_ARTIFACTS_PORT = 8084

    /** Local development port of the git server. */
    const val LOCAL_GIT_PORT = 8091

    private val apiPrefixes = listOf("api.", "studio.", "www.", "ws.", "upload.")
    private val localHosts = setOf("localhost", "127.0.0.1", "[::1]")

    /** The Artifacts registry host for the deployment serving [endpoint]. */
    fun artifactsRegistry(endpoint: String): String = serviceHost(endpoint, "artifacts", LOCAL_ARTIFACTS_PORT)

    /** The git server host for the deployment serving [endpoint]. */
    fun gitHost(endpoint: String): String = serviceHost(endpoint, "git", LOCAL_GIT_PORT)

    private fun serviceHost(endpoint: String, service: String, localPort: Int): String {
        val host = runCatching { URI(endpoint.trim()).host }.getOrNull()?.lowercase()
        require(!host.isNullOrEmpty()) { "Cannot derive the $service host from endpoint '$endpoint'" }
        if (host in localHosts) return "localhost:$localPort"
        val domain = apiPrefixes.firstOrNull { host.startsWith(it) }?.let { host.removePrefix(it) } ?: host
        return "$service.$domain"
    }
}

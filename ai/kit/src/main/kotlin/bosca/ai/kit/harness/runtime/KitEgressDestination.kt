package bosca.ai.kit.harness.runtime

import java.net.URI

private val HOST = Regex("(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")

/** Normalizes an approved `host[:port]` egress destination to `host:port`; the port defaults to HTTPS. */
internal fun egressDestination(value: String): String {
    val normalized = value.trim().lowercase()
    val host = normalized.substringBefore(':').removeSuffix(".")
    val port = if (':' in normalized) normalized.substringAfter(':').toIntOrNull() else 443
    require(port != null && port in 1..65535 && host.matches(HOST)) { "Egress destination must be host or host:port: $value" }
    return "$host:$port"
}

/** The destination of an approved remote MCP endpoint, or null when its host is an IPv6 literal the proxy does not accept. */
internal fun egressDestination(endpoint: URI): String? {
    val host = endpoint.host ?: return null
    if (host.startsWith("[")) return null
    val port = if (endpoint.port > 0) endpoint.port else if (endpoint.scheme == "http") 80 else 443
    return egressDestination("$host:$port")
}

/** Requires an `http://host:port` proxy origin without credentials, path, query or fragment. */
internal fun requireEgressProxy(value: String) {
    val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { null }
    require(uri != null && uri.scheme == "http" && !uri.host.isNullOrBlank() && uri.port in 1..65535 && uri.userInfo == null &&
        uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.fragment == null) { "Egress proxy must be an http://host:port origin" }
}

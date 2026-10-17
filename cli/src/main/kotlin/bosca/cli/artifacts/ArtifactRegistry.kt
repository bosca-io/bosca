package bosca.cli.artifacts

import java.net.URI

/** Resolves a registry host or URL, using HTTP for local development and HTTPS otherwise. */
internal fun artifactRegistryUrl(registry: String): URI {
    val value = registry.trim()
    val uri = URI.create(if ("://" in value) value else "https://$value")
    require(
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.orEmpty() in setOf("", "/"),
    ) { "Artifacts registry must be a hostname or an HTTP(S) URL without a path or credentials" }
    val scheme = if ("://" !in value && uri.host.lowercase() in setOf("localhost", "127.0.0.1", "[::1]")) {
        "http"
    } else {
        uri.scheme
    }
    return URI("$scheme://${uri.rawAuthority}")
}

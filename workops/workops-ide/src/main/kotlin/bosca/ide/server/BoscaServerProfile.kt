package bosca.ide.server

import java.net.URI
import java.util.UUID

/** Non-secret connection metadata for one Bosca server and its Bosca CLI profile. */
data class BoscaServerProfile(
    var id: String = "",
    var name: String = "",
    var graphqlEndpoint: String = "",
    var webSocketEndpoint: String = "",
    var cliProfileName: String = "",
) {
    fun normalized(): BoscaServerProfile {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Server name is required" }

        val graphql = normalizeEndpoint(graphqlEndpoint, setOf("http", "https"), "GraphQL")
        val webSocket = if (webSocketEndpoint.isBlank()) {
            deriveWebSocketEndpoint(graphql)
        } else {
            normalizeEndpoint(webSocketEndpoint, setOf("ws", "wss"), "WebSocket")
        }
        return copy(
            id = id.ifBlank { UUID.randomUUID().toString() },
            name = normalizedName,
            graphqlEndpoint = graphql,
            webSocketEndpoint = webSocket,
            cliProfileName = cliProfileName.trim(),
        )
    }

    companion object {
        private fun normalizeEndpoint(value: String, schemes: Set<String>, label: String): String {
            val uri = runCatching { URI(value.trim()) }
                .getOrElse { throw IllegalArgumentException("$label endpoint is not a valid URI", it) }
            require(uri.scheme?.lowercase() in schemes && uri.host != null) {
                "$label endpoint must use ${schemes.joinToString(" or ")}"
            }
            require(uri.userInfo == null) { "$label endpoint must not contain credentials" }
            return uri.normalize().toString().removeSuffix("/")
        }

        private fun deriveWebSocketEndpoint(graphqlEndpoint: String): String {
            val graphql = URI(graphqlEndpoint)
            val scheme = if (graphql.scheme.equals("https", ignoreCase = true)) "wss" else "ws"
            return URI(scheme, null, graphql.host, graphql.port, "/ws", null, null).toString()
        }
    }
}

/** A collision-proof identifier for an entity hosted by a particular Bosca server. */
data class BoscaEntityKey(val serverProfileId: String, val entityId: String)

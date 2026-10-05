package bosca.gateway.configuration

import bosca.server.BoscaApplication

/**
 * Resolved configuration for the *out-of-process* Rust gateway proxy that
 * polls this server for its routing topology.
 *
 * The proxy authenticates to `/api/v1/gateway/config` with a fixed bearer
 * token shared between this YAML and the proxy's TOML. The capability
 * granted by that token is intentionally minimal: it can read the
 * gateway config document and nothing else. It is not a principal, has
 * no group membership, and cannot be exchanged for broader access. This
 * matches the proxy's actual need — it polls, it never writes.
 *
 * Setting [sharedToken] to null or blank disables the bypass entirely.
 * A deployment that prefers the legacy "admin user with [bosca.gateway.model.GatewayGroups.ADMIN]
 * fetches the config" model can leave the env var unset and the route
 * will continue to require an authenticated admin caller as before.
 *
 * @property sharedToken The exact byte sequence the proxy must present
 *   in `Authorization: Bearer <token>` to read the gateway config.
 *   Null or blank disables the bypass.
 */
data class GatewayProxyConfig(
    val sharedToken: String?,
) {

    companion object {

        fun load(application: BoscaApplication): GatewayProxyConfig {
            val token = application.environment.config
                .propertyOrNull("gateway.proxy.sharedToken")
                ?.getString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            return GatewayProxyConfig(sharedToken = token)
        }
    }
}

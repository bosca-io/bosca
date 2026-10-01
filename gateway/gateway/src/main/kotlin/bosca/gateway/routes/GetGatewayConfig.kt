package bosca.gateway.routes

import bosca.gateway.configuration.GatewayProxyConfig
import bosca.gateway.model.GatewayConfig
import bosca.gateway.service.GatewayConfigService
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * REST shim exposing the gateway configuration document to the Rust
 * proxy. This endpoint has exactly one consumer — the proxy — and
 * exactly one credential — the shared bearer token configured via
 * [GatewayProxyConfig.sharedToken]. Authentication is owned end-to-end
 * by this handler:
 *
 *  - [RouteController.authentication] is [RouteAuthentication.NONE], so
 *    the framework's [bosca.security.routes.BoscaAuthMiddleware] never
 *    runs against this path. That matters because the middleware would
 *    otherwise reject any non-JWT, non-`bsk_*` bearer token with 401
 *    *before* the handler could examine it.
 *  - The handler reads `Authorization: Bearer <token>` directly and
 *    constant-time-compares it ([MessageDigest.isEqual]) against the
 *    configured value. Match → serve. No match (including no header) →
 *    401.
 *
 * The capability granted is intentionally narrow: this one route,
 * read-only, no principal context. The token cannot be exchanged for
 * any broader access. Admin tooling that needs gateway data uses the
 * GraphQL surface, which has its own role-gated authorization.
 *
 * **Security envelope:**
 *
 *  1. The constant-time comparison denies a timing oracle on the token.
 *  2. Setting [GatewayProxyConfig.sharedToken] to null or blank disables
 *     this endpoint entirely — every request gets 401. A deployment
 *     that doesn't run a Rust proxy can drop the YAML key and the route
 *     becomes inert.
 *  3. Conditional fetch via `If-None-Match`: when the client supplies
 *     its current config version, the server short-circuits with `304
 *     Not Modified` after a single version-table query — the proxy
 *     fleet doesn't pull the full payload until something actually
 *     changes.
 */
@RouteController(
    path = "/api/v1/gateway/config",
    authentication = RouteAuthentication.NONE,
)
class GetGatewayConfig(
    private val configService: GatewayConfigService,
    private val proxyConfig: GatewayProxyConfig,
) : Route<GatewayConfig>() {

    override fun serializer(): KSerializer<GatewayConfig> = serializer<GatewayConfig>()

    public override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): GatewayConfig? {
        if (!matchesSharedToken(call)) {
            call.respond(HttpStatusCode.Unauthorized, "")
            return null
        }

        val currentVersion = configService.getVersion()
        val ifNoneMatch = call.request.headers["If-None-Match"]?.trim('"', ' ')
        if (ifNoneMatch != null && ifNoneMatch == currentVersion) {
            call.response.header("ETag", "\"$currentVersion\"")
            call.respond(HttpStatusCode.NotModified)
            return null
        }

        val config = configService.getConfig()
        call.response.header("ETag", "\"${config.version}\"")
        // Discourage caching by intermediaries — the version is the
        // primary cache key, and downstream caches that ignore it would
        // serve stale routing data to the proxy.
        call.response.header("Cache-Control", "no-cache, no-store, must-revalidate")
        return config
    }

    private fun matchesSharedToken(call: ServerCall): Boolean {
        val configured = proxyConfig.sharedToken?.takeIf { it.isNotEmpty() } ?: return false
        val authHeader = call.request.headers["Authorization"]?.trim() ?: return false
        if (!authHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) return false
        val presented = authHeader.substring(BEARER_PREFIX.length).trim()
        if (presented.isEmpty()) return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            configured.toByteArray(Charsets.UTF_8),
        )
    }

    private companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}

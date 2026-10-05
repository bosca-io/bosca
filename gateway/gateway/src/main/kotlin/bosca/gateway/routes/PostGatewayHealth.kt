package bosca.gateway.routes

import bosca.gateway.configuration.GatewayProxyConfig
import bosca.gateway.model.GatewayHealthReport
import bosca.gateway.service.GatewayService
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

/**
 * REST endpoint the Rust proxy POSTs to whenever its per-upstream
 * health state machine detects a transition. Body shape is
 * [GatewayHealthReport]. The endpoint is idempotent: a report whose
 * status matches the row's current `health_status` is a 200 no-op,
 * because two proxy replicas reporting independently will often agree.
 *
 * **Authentication.** Same envelope as [GetGatewayConfig]:
 *
 *  - [RouteController.authentication] is [RouteAuthentication.NONE] so
 *    the framework's middleware doesn't 401 the proxy's non-JWT bearer
 *    token before this handler runs.
 *  - The handler validates `Authorization: Bearer <token>` against
 *    [GatewayProxyConfig.sharedToken] with constant-time comparison.
 *
 * **Why this is narrow capability.** A leaked token can only flip
 * health flags — it can't read upstream URLs (that's GET /config),
 * can't change routes, can't read principals. Worst case from a
 * compromise: a bad actor toggles "Trino is down" repeatedly, causing
 * UI confusion. No PII, no traffic redirection.
 *
 * **Why the status enum lives in the body and not the path.** Putting
 * `up` / `down` in the URL would let a misconfigured caching proxy
 * conflate two transitions into one (same URL, different intent).
 * Body-only keeps every request distinct.
 */
@RouteController(
    path = "/api/v1/gateway/services/{id}/health",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.NONE,
)
class PostGatewayHealth(
    private val gatewayService: GatewayService,
    private val proxyConfig: GatewayProxyConfig,
) : Route<Unit>() {

    override fun serializer(): KSerializer<Unit> = Unit.serializer()

    public override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        if (!matchesSharedToken(call)) {
            call.respond(HttpStatusCode.Unauthorized, "")
            return
        }

        val idParam = call.pathParameters["id"]
        if (idParam.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "missing gateway id")
            return
        }
        val id = try {
            UUID.parse(idParam)
        } catch (_: IllegalArgumentException) {
            // A malformed UUID is a client bug — fail loudly with 400.
            // Returning 404 would let a probe enumerate which IDs exist.
            call.respond(HttpStatusCode.BadRequest, "malformed gateway id")
            return
        }

        val report = call.receive<GatewayHealthReport>()
        try {
            gatewayService.updateHealth(id, report.status, report.reason)
            // 204 No Content whether or not the row actually changed —
            // the proxy doesn't need to know. The DB layer's
            // `where status <> :status` guard does transition detection.
            call.respond(HttpStatusCode.NoContent)
        } catch (e: Exception) {
            // GatewayNotFoundException etc. — surface as 404 so the
            // proxy's poll loop can drop stale gateway IDs. Any other
            // exception is genuinely server-side and gets a 500.
            val message = e.message ?: "unknown"
            if (message.contains("not found", ignoreCase = true)) {
                call.respond(HttpStatusCode.NotFound, "gateway not found")
            } else {
                throw e
            }
        }
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

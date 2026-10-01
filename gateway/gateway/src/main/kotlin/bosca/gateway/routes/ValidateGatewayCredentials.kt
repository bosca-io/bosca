package bosca.gateway.routes

import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Wire shape returned to the Rust proxy's `BasicAuthValidator`. The
 * proxy expects exactly these fields (see `proxy/src/auth/basic.rs`);
 * any rename here breaks the proxy's deserialization. Field-level
 * notes:
 *
 *  - `subject` is the principal UUID as a string — used as the
 *    upstream identity header in `{{user.subject}}` templates.
 *  - `email` and `name` are populated when the principal has a
 *    primary Profile with those attributes. The proxy treats them as
 *    optional — a missing email simply causes any
 *    `{{user.email}}`-bearing inject header to be dropped from the
 *    upstream request rather than rendered as an empty string.
 *  - `groups` is the principal's group name list. Gateway route
 *    `readGroups` / `writeGroups` checks compare against this list,
 *    so name-equality matters.
 *  - `scopes` is non-null when the credential was an API token. The
 *    proxy enforces `gateway:read` / `gateway:write` against this set
 *    on a per-request-method basis. Username/password credentials and
 *    JWT sessions resolve `scopes = null` (unrestricted at the proxy
 *    layer — the route's group check is the only gate).
 */
@Serializable
data class GatewayValidationResponse(
    val valid: Boolean,
    val subject: String? = null,
    val email: String? = null,
    val name: String? = null,
    val groups: List<String> = emptyList(),
    val scopes: List<String>? = null,
)

/**
 * REST endpoint the Rust proxy calls to validate Basic-auth
 * credentials it received from clients. The proxy doesn't carry any
 * Bosca-specific knowledge of token formats — it forwards the
 * decoded `username` and `password` to this endpoint via standard
 * Basic auth and trusts our reply.
 *
 * **How auth happens here:**
 *
 *   - The route is annotated [RouteAuthentication.REQUIRED] so
 *     [bosca.security.routes.BoscaAuthMiddleware] runs first. That
 *     middleware already understands:
 *       * `Authorization: Bearer bsk_*` → API token
 *       * `Authorization: Bearer <JWT>` → session JWT
 *       * `Authorization: Basic api_token:bsk_*` → API token tunneled
 *         through Basic (the standard pattern for tools that don't
 *         emit Bearer headers — `curl -u api_token:bsk_*`, JDBC
 *         drivers, etc.)
 *       * `Authorization: Basic <user>:<password>` → password
 *         credential
 *   - If any of those resolves a principal, the framework attaches it
 *     to the call's [AuthenticationContext] and we run.
 *   - If none resolve, the framework responds 401 before this handler
 *     executes — and the proxy sees an HTTP non-2xx, which it maps to
 *     `AuthError::Invalid` and returns 401 to the original client.
 *
 * **What we return:**
 *
 *   - On success, the [GatewayValidationResponse] above.
 *   - The endpoint is intentionally a thin reflection — it never
 *     performs additional authorization (no group/scope check). The
 *     proxy decides what to do with the returned identity based on
 *     each route's own group gating.
 *
 * **Security envelope:**
 *
 *   - The endpoint is mounted under the gateway path prefix by
 *     convention so deployments that don't run a Rust proxy can
 *     block it at the reverse proxy / ingress layer if desired. The
 *     handler doesn't grant any capability beyond "tell me who I am",
 *     which is the same information the principal could read via
 *     existing `/security/principal` queries.
 *   - The principal's group **names** are returned (not UUIDs). The
 *     proxy's route gating compares names, matching how groups
 *     surface in JWT claims and elsewhere in the stack.
 *   - Caching: the proxy already caches successful validation
 *     responses (`auth.basic.cache_ttl_secs`), so this handler does
 *     no caching of its own — the freshest principal state is
 *     fetched on every cache miss.
 */
@RouteController(
    path = "/api/v1/gateway/validate",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class ValidateGatewayCredentials(
    private val securityService: SecurityService,
) : Route<GatewayValidationResponse>() {

    override fun serializer(): KSerializer<GatewayValidationResponse> =
        serializer<GatewayValidationResponse>()

    public override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): GatewayValidationResponse? {
        val principal = authenticationContext.principal()
        if (principal == null) {
            // RouteAuthentication.REQUIRED should have prevented this,
            // but a misconfigured route or a middleware bug could
            // leave us here. Fail closed.
            call.respond(HttpStatusCode.Unauthorized, "")
            return null
        }

        // Fetch group names — AuthenticatedPrincipal keeps its group
        // list private, so we re-resolve from the SecurityService.
        // This is a hot path but the proxy's per-credential cache
        // (cache_ttl_secs) means we serve maybe one request per
        // unique (user, password) pair per cache window per proxy.
        val groups = securityService.getPrincipalGroups(principal.id).map { it.name }

        // API tokens carry scope restrictions that gateway routes
        // additionally enforce (gateway:read / gateway:write). Non-token
        // principals (password, session JWT) have null scopes —
        // semantically "unrestricted at the proxy layer".
        val scopes = (principal as? ScopedAuthenticatedPrincipal)?.scopes?.toList()

        return GatewayValidationResponse(
            valid = true,
            subject = principal.id.toString(),
            email = null,
            name = null,
            groups = groups,
            scopes = scopes,
        )
    }
}

package bosca.artifacts.npm.routes

import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Handles the npm login flow. npm clients PUT to this endpoint to validate
 * credentials and receive a token. In Bosca, actual token management is
 * handled by the security system — this endpoint confirms the caller has
 * a valid scoped API token with artifact registry access.
 *
 * Only scoped API tokens (`bsk_` prefix) with `artifacts:` scopes are accepted,
 * matching the authentication model enforced by all other registry endpoints.
 * JWT and session principals should use the admin GraphQL API instead.
 */
@RouteController("/npm/-/user/{user}", method = RouteMethod.PUT, authentication = RouteAuthentication.OPTIONAL)
class NpmLogin : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val principal = authenticationContext.principal()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject { put("error", "unauthorized") })
            return
        }
        if (principal !is ScopedAuthenticatedPrincipal) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("error", "registry access requires a scoped API token (bsk_ prefix) — JWT and session authentication are not supported for registry endpoints")
            })
            return
        }
        val scopes = principal.scopes
        if (scopes == null || scopes.none { it.startsWith("artifacts:") }) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("error", "API token must have at least one artifacts: scope for registry access")
            })
            return
        }
        val body = buildJsonObject {
            put("ok", "you are authenticated as '${principal.id}'")
            put("id", "org.couchdb.user:${principal.id}")
        }
        call.respond(HttpStatusCode.Created, body)
    }
}

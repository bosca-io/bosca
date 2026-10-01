package bosca.artifacts.npm.routes

import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Returns the authenticated user's identity. Used by `npm whoami` to verify
 * that the client's credentials are valid.
 */
@RouteController("/npm/-/whoami", authentication = RouteAuthentication.OPTIONAL)
class NpmWhoami : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val principal = authenticationContext.principal()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject { put("error", "not authenticated") })
            return
        }
        call.respond(HttpStatusCode.OK, buildJsonObject { put("username", principal.id.toString()) })
    }
}

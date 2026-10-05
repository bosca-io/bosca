package bosca.artifacts.docker.routes

import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.json.buildJsonObject

/**
 * Docker registry version check and authentication entry point. Per the OCI Distribution
 * Spec, this endpoint serves as the auth challenge endpoint — unauthenticated callers
 * receive a 401 with `WWW-Authenticate` so Docker clients know to send credentials on
 * all subsequent requests. Authenticated callers receive 200 to confirm API version support.
 */
@RouteController("/v2/", authentication = RouteAuthentication.OPTIONAL)
class DockerVersionCheck : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        if (authenticationContext.principal() == null) {
            call.response.header("WWW-Authenticate", """Basic realm="Bosca Registry"""")
            call.respond(HttpStatusCode.Unauthorized, dockerError("UNAUTHORIZED", "authentication required"))
            return
        }
        call.respond(HttpStatusCode.OK, buildJsonObject { })
    }
}

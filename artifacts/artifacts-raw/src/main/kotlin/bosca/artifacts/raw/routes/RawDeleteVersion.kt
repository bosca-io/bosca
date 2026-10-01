package bosca.artifacts.raw.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * Removes a specific version of a raw artifact. Requires admin scope.
 */
@RouteController("/raw/{namespace}/api/{name}/{version}", method = RouteMethod.DELETE, authentication = RouteAuthentication.OPTIONAL)
class RawDeleteVersion(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val name = call.pathParameters["name"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val version = call.pathParameters["version"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }

        if (!rawRequirePermission(call, permissionEvaluator, authenticationContext, namespace, name, version, ArtifactAction.ADMIN)) {
            return
        }

        val repo = repoService.findRepository(namespace, name, ArtifactType.RAW)
        if (repo == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val artifactVersion = repoService.findVersion(repo.id, version)
        if (artifactVersion == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        repoService.deleteVersion(artifactVersion.id)
        call.respond(HttpStatusCode.OK, "Version $name@$version deleted")
    }
}

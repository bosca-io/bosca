package bosca.artifacts.docker.routes

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
 * Deletes a manifest or tag from a Docker repository.
 */
@RouteController("/v2/{namespace}/{repo}/manifests/{reference}", method = RouteMethod.DELETE, authentication = RouteAuthentication.OPTIONAL)
class DockerDeleteManifest(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: return call.respond(HttpStatusCode.NotFound)
        val repoName = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.NotFound)
        val reference = call.pathParameters["reference"] ?: return call.respond(HttpStatusCode.NotFound)
        if (!dockerRequirePermission(call, permissionEvaluator, authenticationContext, "docker", namespace, repoName, reference, ArtifactAction.ADMIN)) return

        val repo = repoService.findRepository(namespace, repoName, ArtifactType.DOCKER)
        if (repo == null) {
            call.respond(HttpStatusCode.NotFound, dockerError("NAME_UNKNOWN", "repository name not known to registry"))
            return
        }

        if (reference.contains(":")) {
            repoService.deleteManifest(repo.id, reference)
        } else {
            repoService.deleteTag(repo.id, reference)
        }

        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        call.respond(HttpStatusCode.Accepted)
    }
}

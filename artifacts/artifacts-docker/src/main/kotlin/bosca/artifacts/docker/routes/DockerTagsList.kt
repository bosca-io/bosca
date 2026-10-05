package bosca.artifacts.docker.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.json.*

/**
 * Lists tags for a Docker repository with pagination support.
 */
@RouteController("/v2/{namespace}/{repo}/tags/list", authentication = RouteAuthentication.OPTIONAL)
class DockerTagsList(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: return call.respond(HttpStatusCode.NotFound)
        val repoName = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.NotFound)
        val ns = repoService.getNamespaceByName(namespace)
        if (!dockerRequirePermission(call, permissionEvaluator, authenticationContext, "docker", namespace, repoName, null, ArtifactAction.PULL, ns?.public ?: false)) return

        val repo = repoService.findRepository(namespace, repoName, ArtifactType.DOCKER)
        if (repo == null) {
            call.respond(HttpStatusCode.NotFound, dockerError("NAME_UNKNOWN", "repository name not known to registry"))
            return
        }

        val n = (call.request.queryParameters["n"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
        val last = call.request.queryParameters["last"]
        val tags = repoService.listTags(repo.id, limit = n, last = last)

        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        call.respond(HttpStatusCode.OK, buildJsonObject {
            put("name", "$namespace/$repoName")
            putJsonArray("tags") { tags.forEach { add(it.name) } }
        })
    }
}

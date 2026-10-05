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
 * Lists Docker repositories in the registry that the caller has pull access to.
 * Public namespace repositories are visible to unauthenticated callers.
 */
@RouteController("/v2/_catalog", authentication = RouteAuthentication.OPTIONAL)
class DockerCatalog(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val n = (call.request.queryParameters["n"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
        val repos = repoService.listRepositoriesByType(ArtifactType.DOCKER, limit = n)
        val names = repos.mapNotNull { repo ->
            val ns = repoService.getNamespace(repo.namespaceId) ?: return@mapNotNull null
            val hasAccess = permissionEvaluator.evaluate(
                authenticationContext, "docker", ns.name, repo.name, null, ArtifactAction.PULL, ns.public
            )
            if (!hasAccess) return@mapNotNull null
            "${ns.name}/${repo.name}"
        }
        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        call.respond(HttpStatusCode.OK, buildJsonObject {
            putJsonArray("repositories") { names.forEach { add(it) } }
        })
    }
}

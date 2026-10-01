package bosca.artifacts.helm.routes

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
 * Removes a specific chart version from the repository. Requires the
 * `artifacts:admin` scope.
 */
@RouteController("/helm/{namespace}/api/charts/{name}/{version}", method = RouteMethod.DELETE, authentication = RouteAuthentication.OPTIONAL)
class HelmDeleteChart(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val chartName = call.pathParameters["name"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val chartVersion = call.pathParameters["version"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }

        if (!helmRequirePermission(call, permissionEvaluator, authenticationContext, namespace, chartName, chartVersion, ArtifactAction.ADMIN)) {
            return
        }

        val repo = repoService.findRepository(namespace, chartName, ArtifactType.HELM)
        if (repo == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val version = repoService.findVersion(repo.id, chartVersion)
        if (version == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        repoService.deleteVersion(version.id)
        call.respond(HttpStatusCode.OK, "Chart ${chartName}-${chartVersion} deleted")
    }
}

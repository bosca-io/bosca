package bosca.artifacts.helm.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * Serves the dynamically generated `index.yaml` for a Helm chart repository
 * namespace. This is the primary discovery endpoint for `helm repo add`.
 */
@RouteController("/helm/{namespace}/index.yaml", authentication = RouteAuthentication.OPTIONAL)
class HelmGetIndex(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val ns = repoService.getNamespaceByName(namespace)
        if (!helmRequirePermission(call, permissionEvaluator, authenticationContext, namespace, action = ArtifactAction.PULL, isPublic = ns?.public ?: false)) {
            return
        }

        val scheme = call.request.header("X-Forwarded-Proto") ?: "https"
        val host = call.request.header("X-Forwarded-Host") ?: call.request.header(HttpHeaders.Host) ?: "localhost"
        val baseUrl = "$scheme://$host"

        val indexYaml = buildIndexYaml(namespace, baseUrl, repoService)

        call.response.header(HttpHeaders.ContentType, "application/x-yaml")
        call.respond(HttpStatusCode.OK, indexYaml)
    }
}

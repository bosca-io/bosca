package bosca.artifacts.ml.routes

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lists the versions of a model repository so a consumer (the model-loader) can pick the newest to pull.
 */
@RouteController("/ml/{namespace}/api/{name}", authentication = RouteAuthentication.OPTIONAL)
class MlListVersions(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {

    @Serializable
    data class VersionInfo(val version: String, val created: String)

    @Serializable
    data class VersionsResponse(val name: String, val versions: List<VersionInfo>)

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val name = call.pathParameters["name"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val ns = repoService.getNamespaceByName(namespace)
        if (!mlRequirePermission(call, permissionEvaluator, authenticationContext, namespace, name, action = ArtifactAction.PULL, isPublic = ns?.public ?: false)) {
            return
        }

        val repo = repoService.findRepository(namespace, name, ArtifactType.ML)
        if (repo == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val versions = mutableListOf<VersionInfo>()
        var offset = 0L
        val pageSize = 500
        while (true) {
            val page = repoService.listVersions(repo.id, pageSize, offset)
            if (page.isEmpty()) break
            page.forEach { versions.add(VersionInfo(version = it.version, created = it.created.toString())) }
            if (page.size < pageSize) break
            offset += pageSize
        }

        call.response.header("Content-Type", "application/json")
        call.respond(HttpStatusCode.OK, Json.encodeToString(VersionsResponse(name = name, versions = versions)))
    }
}

package bosca.artifacts.raw.routes

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
 * Lists the raw repositories of a namespace — the discovery surface consumers use to find
 * everything published under it without knowing names up front (e.g. the BML Email Template
 * Server discovering hosted message projects at warmup instead of relying on seeds or on
 * catching live publish events).
 */
@RouteController("/raw/{namespace}/api", authentication = RouteAuthentication.OPTIONAL)
class RawListRepositories(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {

    @Serializable
    data class RepositoriesResponse(val namespace: String, val repositories: List<String>)

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val ns = repoService.getNamespaceByName(namespace)
        if (!rawRequirePermission(call, permissionEvaluator, authenticationContext, namespace, action = ArtifactAction.PULL, isPublic = ns?.public ?: false)) {
            return
        }
        if (ns == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val names = mutableListOf<String>()
        var offset = 0L
        val pageSize = 500
        while (true) {
            val page = repoService.listRepositories(ns.id, ArtifactType.RAW, pageSize, offset)
            if (page.isEmpty()) break
            names += page.map { it.name }
            if (page.size < pageSize) break
            offset += pageSize
        }
        call.response.header("Content-Type", "application/json")
        call.respond(HttpStatusCode.OK, Json.encodeToString(RepositoriesResponse.serializer(), RepositoriesResponse(namespace, names.sorted())))
    }
}

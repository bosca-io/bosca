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
 * Lists all versions (with their files) for a raw artifact repository.
 */
@RouteController("/raw/{namespace}/api/{name}", authentication = RouteAuthentication.OPTIONAL)
class RawListVersions(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {

    @Serializable
    data class FileInfo(val filename: String, val digest: String, val mediaType: String?)

    @Serializable
    data class VersionInfo(val version: String, val created: String, val files: List<FileInfo>)

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
        if (!rawRequirePermission(call, permissionEvaluator, authenticationContext, namespace, name, action = ArtifactAction.PULL, isPublic = ns?.public ?: false)) {
            return
        }

        val repo = repoService.findRepository(namespace, name, ArtifactType.RAW)
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

            for (v in page) {
                val blobs = repoService.getVersionBlobs(v.id)
                versions.add(VersionInfo(
                    version = v.version,
                    created = v.created.toString(),
                    files = blobs.map { FileInfo(it.filename ?: "", it.digest, it.mediaType) },
                ))
            }

            if (page.size < pageSize) break
            offset += pageSize
        }

        val response = VersionsResponse(name = name, versions = versions)
        call.response.header("Content-Type", "application/json")
        call.respond(HttpStatusCode.OK, Json.encodeToString(response))
    }
}

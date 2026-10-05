package bosca.artifacts.docker.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * Returns the status of an in-progress chunked blob upload. Docker clients
 * use this to resume interrupted uploads by discovering how many bytes the
 * registry has already received.
 */
@RouteController("/v2/{namespace}/{repo}/blobs/uploads/{uuid}", authentication = RouteAuthentication.OPTIONAL)
class DockerGetBlobUploadStatus(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: return call.respond(HttpStatusCode.NotFound)
        val repoName = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.NotFound)
        val uuid = call.pathParameters["uuid"] ?: return call.respond(HttpStatusCode.NotFound)
        if (!dockerRequirePermission(call, permissionEvaluator, authenticationContext, "docker", namespace, repoName, null, ArtifactAction.PUSH)) return

        val sessionId = UUID.parse(uuid)
        val session = repoService.getUploadSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, dockerError("BLOB_UPLOAD_UNKNOWN", "upload session not found"))
            return
        }

        val repo = repoService.findRepository(namespace, repoName, ArtifactType.DOCKER)
        if (repo == null || session.repositoryId != repo.id) {
            call.respond(HttpStatusCode.NotFound, dockerError("BLOB_UPLOAD_UNKNOWN", "upload session not found"))
            return
        }

        val range = if (session.byteOffset > 0) "0-${session.byteOffset - 1}" else "0-0"

        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        call.response.header(HttpHeaders.Location, "/v2/$namespace/$repoName/blobs/uploads/$uuid")
        call.response.header("Docker-Upload-UUID", uuid)
        call.response.header("Range", range)
        call.respond(HttpStatusCode.NoContent)
    }
}

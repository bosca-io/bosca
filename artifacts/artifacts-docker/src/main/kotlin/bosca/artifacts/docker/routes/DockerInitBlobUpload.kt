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
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * Initiates a chunked blob upload session. Returns a UUID that the client
 * uses in subsequent PATCH and PUT requests to upload data.
 */
@RouteController("/v2/{namespace}/{repo}/blobs/uploads/", method = RouteMethod.POST, authentication = RouteAuthentication.OPTIONAL)
class DockerInitBlobUpload(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: return call.respond(HttpStatusCode.NotFound)
        val repoName = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.NotFound)
        if (!dockerRequirePermission(call, permissionEvaluator, authenticationContext, "docker", namespace, repoName, null, ArtifactAction.PUSH)) return

        // Reconcile abandoned storage work opportunistically so cleanup does not depend on a
        // provider-specific object lifecycle policy or a separately configured scheduler.
        repoService.cleanupExpiredUploadSessions()
        val repo = repoService.findOrCreateRepository(namespace, repoName, ArtifactType.DOCKER)
        val session = repoService.createUploadSession(repo.id)

        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        call.response.header(HttpHeaders.Location, "/v2/$namespace/$repoName/blobs/uploads/${session.id}")
        call.response.header("Docker-Upload-UUID", session.id.toString())
        call.response.header("Range", "0-0")
        call.response.header("OCI-Chunk-Min-Length", MIN_MULTIPART_PART_SIZE.toString())
        call.respond(HttpStatusCode.Accepted)
    }

    private companion object {
        const val MIN_MULTIPART_PART_SIZE = 5L * 1024L * 1024L
    }
}

package bosca.artifacts.docker.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.ContentType
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * Downloads a blob by its content digest from a Docker repository.
 */
@RouteController("/v2/{namespace}/{repo}/blobs/{digest}", authentication = RouteAuthentication.OPTIONAL)
class DockerGetBlob(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: return call.respond(HttpStatusCode.NotFound)
        val repoName = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.NotFound)
        val digest = call.pathParameters["digest"] ?: return call.respond(HttpStatusCode.NotFound)
        val ns = repoService.getNamespaceByName(namespace)
        if (!dockerRequirePermission(call, permissionEvaluator, authenticationContext, "docker", namespace, repoName, null, ArtifactAction.PULL, ns?.public ?: false)) return

        val blob = blobService.get(digest)
        if (blob == null) {
            call.respond(HttpStatusCode.NotFound, dockerError("BLOB_UNKNOWN", "blob unknown to registry"))
            return
        }

        // Opened before the blob's headers are set, so a storage failure answers without them.
        val stream = blobService.getInputStream(digest)

        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        call.response.header("Docker-Content-Digest", digest)
        call.response.header(HttpHeaders.ContentLength, blob.size.toString())
        call.response.header(HttpHeaders.ETag, "\"$digest\"")

        // Closed whatever happens: a HEAD request is answered without running the streaming block.
        stream.use { input ->
            // No time limit: a large blob on a slow link may take long; a client that stops reading
            // altogether is ended by the streaming response's stall timeout.
            call.respondStreaming(ContentType.Application.OctetStream, HttpStatusCode.OK, timeLimit = null) { output -> output.copyFrom(input) }
        }
    }
}

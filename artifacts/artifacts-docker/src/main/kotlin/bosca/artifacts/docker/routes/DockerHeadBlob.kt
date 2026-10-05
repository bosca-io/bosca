package bosca.artifacts.docker.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * Checks whether a blob exists in a Docker repository without transferring
 * its content. Docker clients use this before pushing layers to avoid
 * re-uploading blobs the registry already has (layer deduplication).
 */
@RouteController("/v2/{namespace}/{repo}/blobs/{digest}", method = RouteMethod.HEAD, authentication = RouteAuthentication.OPTIONAL)
class DockerHeadBlob(
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

        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        call.response.header("Docker-Content-Digest", digest)
        call.response.header(HttpHeaders.ContentLength, blob.size.toString())
        call.response.header(HttpHeaders.ETag, "\"$digest\"")
        call.respond(HttpStatusCode.OK)
    }
}

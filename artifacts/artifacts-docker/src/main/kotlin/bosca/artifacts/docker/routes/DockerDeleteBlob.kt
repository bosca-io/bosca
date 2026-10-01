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
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * Deletes a blob from the registry by its content digest. The blob's reference
 * count is decremented; if no versions reference it, the underlying storage
 * object is removed.
 */
@RouteController("/v2/{namespace}/{repo}/blobs/{digest}", method = RouteMethod.DELETE, authentication = RouteAuthentication.OPTIONAL)
class DockerDeleteBlob(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: return call.respond(HttpStatusCode.NotFound)
        val repoName = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.NotFound)
        val digest = call.pathParameters["digest"] ?: return call.respond(HttpStatusCode.NotFound)
        if (!dockerRequirePermission(call, permissionEvaluator, authenticationContext, "docker", namespace, repoName, null, ArtifactAction.ADMIN)) return

        val blob = blobService.get(digest)
        if (blob == null) {
            call.respond(HttpStatusCode.NotFound, dockerError("BLOB_UNKNOWN", "blob unknown to registry"))
            return
        }

        blobService.deleteIfUnreferenced(digest)

        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        call.respond(HttpStatusCode.Accepted)
    }
}

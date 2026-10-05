package bosca.artifacts.docker.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.server.ContentType
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * Checks whether a manifest exists in a Docker repository and returns its
 * digest, size, and media type without transferring the body. Docker clients
 * use this to resolve tags to digests and to verify manifest existence
 * before pulling layers.
 */
@RouteController("/v2/{namespace}/{repo}/manifests/{reference}", method = RouteMethod.HEAD, authentication = RouteAuthentication.OPTIONAL)
class DockerHeadManifest(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: return call.respond(HttpStatusCode.NotFound)
        val repoName = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.NotFound)
        val reference = call.pathParameters["reference"] ?: return call.respond(HttpStatusCode.NotFound)
        val ns = repoService.getNamespaceByName(namespace)
        if (!dockerRequirePermission(call, permissionEvaluator, authenticationContext, "docker", namespace, repoName, reference, ArtifactAction.PULL, ns?.public ?: false)) return

        val repo = repoService.findRepository(namespace, repoName, ArtifactType.DOCKER)
        if (repo == null) {
            call.respond(HttpStatusCode.NotFound, dockerError("NAME_UNKNOWN", "repository name not known to registry"))
            return
        }

        val digest = if (reference.contains(":")) {
            reference
        } else {
            val tag = repoService.findTag(repo.id, reference)
            if (tag == null) {
                call.respond(HttpStatusCode.NotFound, dockerError("MANIFEST_UNKNOWN", "manifest unknown to registry"))
                return
            }
            tag.manifestDigest
        }

        val version = repoService.findVersion(repo.id, digest)
        val blob = blobService.get(digest)
        if (blob == null) {
            call.respond(HttpStatusCode.NotFound, dockerError("MANIFEST_UNKNOWN", "manifest unknown to registry"))
            return
        }

        val versionBlobs = version?.let { repoService.getVersionBlobs(it.id) }
        val manifestBlob = versionBlobs?.find { it.role == "manifest" }
        val mediaType = sanitizeManifestMediaType(manifestBlob?.mediaType)

        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        call.response.header("Docker-Content-Digest", digest)
        call.response.header(HttpHeaders.ContentLength, blob.size.toString())
        call.response.header(HttpHeaders.ContentType, mediaType)
        call.response.header(HttpHeaders.ETag, "\"$digest\"")
        call.respond(HttpStatusCode.OK)
    }
}

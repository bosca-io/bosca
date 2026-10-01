package bosca.artifacts.docker.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
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
 * Retrieves a manifest by tag or digest from a Docker repository.
 *
 * The response Content-Type is set to the manifest's stored media type
 * (e.g. `application/vnd.docker.distribution.manifest.v2+json`), which
 * Docker/OCI clients require for correct manifest parsing.
 */
@RouteController("/v2/{namespace}/{repo}/manifests/{reference}", authentication = RouteAuthentication.OPTIONAL)
class DockerGetManifest(
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

        // Look up the version to get the stored media type from the manifest blob
        val version = repoService.findVersion(repo.id, digest)
        val blob = blobService.get(digest)
        if (blob == null) {
            call.respond(HttpStatusCode.NotFound, dockerError("MANIFEST_UNKNOWN", "manifest unknown to registry"))
            return
        }

        // Determine the Content-Type from the version metadata (set during PUT),
        // sanitized against known OCI/Docker manifest types to prevent content-type
        // confusion from attacker-controlled headers stored during push.
        val versionBlobs = version?.let { repoService.getVersionBlobs(it.id) }
        val manifestBlob = versionBlobs?.find { it.role == "manifest" }
        val mediaType = sanitizeManifestMediaType(manifestBlob?.mediaType)

        // Opened before the manifest's headers are set, so a storage failure answers without them.
        val stream = blobService.getInputStream(digest)

        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        call.response.header("Docker-Content-Digest", digest)
        call.response.header(HttpHeaders.ContentLength, blob.size.toString())
        call.response.header(HttpHeaders.ETag, "\"$digest\"")

        // Closed whatever happens: a HEAD request never runs the streaming block.
        stream.use { input ->
            call.respondStreaming(ContentType.parse(mediaType)) { output -> output.copyFrom(input) }
        }
    }
}

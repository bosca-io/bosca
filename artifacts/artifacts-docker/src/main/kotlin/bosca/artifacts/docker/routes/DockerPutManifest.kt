package bosca.artifacts.docker.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.db.transaction
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedOutputStream
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Pushes a manifest to a Docker repository, optionally tagging it.
 */
@RouteController("/v2/{namespace}/{repo}/manifests/{reference}", method = RouteMethod.PUT, authentication = RouteAuthentication.OPTIONAL)
class DockerPutManifest(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: return call.respond(HttpStatusCode.NotFound)
        val repoName = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.NotFound)
        val reference = call.pathParameters["reference"] ?: return call.respond(HttpStatusCode.NotFound)
        if (!dockerRequirePermission(call, permissionEvaluator, authenticationContext, "docker", namespace, repoName, reference, ArtifactAction.PUSH)) return

        val sha256 = MessageDigest.getInstance("SHA-256")
        val tempFile = withContext(Dispatchers.IO) { File.createTempFile("docker-manifest-", ".tmp") }
        try {
            val bodySize = withContext(Dispatchers.IO) {
                DigestOutputStream(BufferedOutputStream(tempFile.outputStream()), sha256).use { output ->
                    call.request.bodyStreamTo(output)
                }
            }

            val digest = "sha256:" + sha256.digest().joinToString("") { "%02x".format(it) }
            withContext(Dispatchers.IO) {
                tempFile.inputStream().use { input ->
                    blobService.store(digest, input, bodySize)
                }
            }

            val repo = repoService.findOrCreateRepository(namespace, repoName, ArtifactType.DOCKER)
            transaction {
                val existing = repoService.findVersion(repo.id, digest)
                if (existing == null) {
                    val contentType = call.request.contentType()?.toString()
                    val metadata = buildJsonObject { contentType?.let { put("mediaType", it) } }
                    val version = repoService.createVersion(repo.id, digest, metadata)
                    repoService.addVersionBlob(version.id, digest, "manifest", null, contentType)
                }

                if (!reference.contains(":")) {
                    repoService.setTag(repo.id, reference, digest)
                }
            }

            call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
            call.response.header("Docker-Content-Digest", digest)
            call.response.header(HttpHeaders.Location, "/v2/$namespace/$repoName/manifests/$digest")
            call.respond(HttpStatusCode.Created)
        } finally {
            withContext(Dispatchers.IO) { tempFile.delete() }
        }
    }
}

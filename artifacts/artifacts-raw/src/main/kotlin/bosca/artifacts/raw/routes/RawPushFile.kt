package bosca.artifacts.raw.routes

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
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedOutputStream
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Accepts a raw file upload and stores it as a versioned artifact.
 * The filename path parameter identifies the file within the version,
 * allowing multiple files per version (e.g. platform-specific binaries).
 */
@RouteController("/raw/{namespace}/api/{name}/{version}/{filename}", method = RouteMethod.PUT, authentication = RouteAuthentication.OPTIONAL)
class RawPushFile(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val name = call.pathParameters["name"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val version = call.pathParameters["version"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val filename = call.pathParameters["filename"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }

        if (!rawRequirePermission(call, permissionEvaluator, authenticationContext, namespace, name, version, ArtifactAction.PUSH)) {
            return
        }

        val mediaType = call.request.header("Content-Type") ?: "application/octet-stream"

        val sha256 = MessageDigest.getInstance("SHA-256")
        val tempFile = withContext(Dispatchers.IO) { File.createTempFile("raw-upload-", ".tmp") }

        try {
            val digestOutput = DigestOutputStream(
                BufferedOutputStream(withContext(Dispatchers.IO) { tempFile.outputStream() }),
                sha256,
            )
            val bytesWritten = digestOutput.use { output ->
                call.request.bodyStreamTo(output)
            }

            val sha256Hex = sha256.digest().joinToString("") { "%02x".format(it) }
            val digest = "sha256:$sha256Hex"

            withContext(Dispatchers.IO) {
                tempFile.inputStream().use { input ->
                    blobService.store(digest, input, bytesWritten)
                }
            }

            val repo = repoService.findOrCreateRepository(namespace, name, ArtifactType.RAW)

            val existingVersion = repoService.findVersion(repo.id, version)
            val artifactVersion = if (existingVersion != null) {
                existingVersion
            } else {
                val metadata = JsonObject(buildMap {
                    put("type", JsonPrimitive("raw"))
                })
                repoService.createVersion(repo.id, version, metadata)
            }

            // Re-pushing the same filename to a version replaces the prior bytes
            // rather than leaving two blobs sharing a filename — so a fixed-path
            // file (e.g. the installer at install/install.sh) always serves the
            // newest upload through the order-less file lookup in RawGetFile.
            repoService.addOrReplaceVersionBlob(artifactVersion.id, digest, "file", filename, mediaType)

            call.respond(HttpStatusCode.Created, "File $filename uploaded to $name@$version")
        } finally {
            withContext(Dispatchers.IO) {
                if (!tempFile.delete()) {
                    org.slf4j.LoggerFactory.getLogger("bosca.artifacts.raw.routes.RawPushFile")
                        .warn("Failed to delete temp file: {}", tempFile.absolutePath)
                }
            }
        }
    }
}

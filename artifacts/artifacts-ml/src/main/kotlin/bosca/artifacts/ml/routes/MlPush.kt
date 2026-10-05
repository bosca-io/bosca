package bosca.artifacts.ml.routes

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

/** The single-file semantics of an ML model artifact version: one tar blob in the "model" role. */
internal const val MODEL_BLOB_ROLE = "model"
internal const val MODEL_BLOB_FILENAME = "model.tar.gz"
internal const val MODEL_BLOB_MEDIA_TYPE = "application/x-tar+gzip"

/**
 * Accepts a trained ML model as a single tar archive and stores it as a versioned `ml` artifact. Unlike raw
 * artifacts there is no per-file path segment — a model version is exactly one tar blob (role "model"), so
 * re-pushing the same version replaces its bytes.
 */
@RouteController("/ml/{namespace}/api/{name}/{version}", method = RouteMethod.PUT, authentication = RouteAuthentication.OPTIONAL)
class MlPush(
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

        if (!mlRequirePermission(call, permissionEvaluator, authenticationContext, namespace, name, version, ArtifactAction.PUSH)) {
            return
        }

        val sha256 = MessageDigest.getInstance("SHA-256")
        val tempFile = withContext(Dispatchers.IO) { File.createTempFile("ml-upload-", ".tmp") }

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

            val repo = repoService.findOrCreateRepository(namespace, name, ArtifactType.ML)

            val existingVersion = repoService.findVersion(repo.id, version)
            val artifactVersion = existingVersion ?: repoService.createVersion(
                repo.id, version, JsonObject(mapOf("type" to JsonPrimitive("ml"))),
            )

            // A model version holds exactly one tar; re-pushing the version replaces its bytes.
            repoService.addOrReplaceVersionBlob(artifactVersion.id, digest, MODEL_BLOB_ROLE, MODEL_BLOB_FILENAME, MODEL_BLOB_MEDIA_TYPE)

            call.respond(HttpStatusCode.Created, "Model $name@$version uploaded")
        } finally {
            withContext(Dispatchers.IO) {
                if (!tempFile.delete()) {
                    org.slf4j.LoggerFactory.getLogger("bosca.artifacts.ml.routes.MlPush")
                        .warn("Failed to delete temp file: {}", tempFile.absolutePath)
                }
            }
        }
    }
}

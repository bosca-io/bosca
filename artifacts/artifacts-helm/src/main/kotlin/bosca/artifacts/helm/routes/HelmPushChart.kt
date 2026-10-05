package bosca.artifacts.helm.routes

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
import bosca.server.HttpHeaders
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
 * Accepts a packaged Helm chart upload (`.tgz`), extracts Chart.yaml metadata,
 * stores the tarball as a blob, and creates the corresponding artifact version.
 */
@RouteController("/helm/{namespace}/api/charts", method = RouteMethod.POST, authentication = RouteAuthentication.OPTIONAL)
class HelmPushChart(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }

        if (!helmRequirePermission(call, permissionEvaluator, authenticationContext, namespace, action = ArtifactAction.PUSH)) {
            return
        }

        val sha256 = MessageDigest.getInstance("SHA-256")
        val tempFile = withContext(Dispatchers.IO) { File.createTempFile("helm-upload-", ".tgz") }

        try {
            val digestOutput = DigestOutputStream(
                BufferedOutputStream(withContext(Dispatchers.IO) { tempFile.outputStream() }),
                sha256,
            )
            val bytesWritten = digestOutput.use { output ->
                call.request.bodyStreamTo(output)
            }

            val chartMeta = withContext(Dispatchers.IO) {
                tempFile.inputStream().use { parseChartArchive(it) }
            }
            if (chartMeta == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid Helm chart: could not parse Chart.yaml from archive")
                return
            }

            val sha256Hex = sha256.digest().joinToString("") { "%02x".format(it) }
            val digest = "sha256:$sha256Hex"

            withContext(Dispatchers.IO) {
                tempFile.inputStream().use { input ->
                    blobService.store(digest, input, bytesWritten)
                }
            }

            val repo = repoService.findOrCreateRepository(namespace, chartMeta.name, ArtifactType.HELM)

            val existing = repoService.findVersion(repo.id, chartMeta.version)
            if (existing != null) {
                call.respond(HttpStatusCode.Conflict, "Chart ${chartMeta.name}-${chartMeta.version} already exists")
                return
            }

            val metadata = JsonObject(buildMap {
                put("digest", JsonPrimitive(digest))
                chartMeta.appVersion?.let { put("appVersion", JsonPrimitive(it)) }
                chartMeta.description?.let { put("description", JsonPrimitive(it)) }
                chartMeta.apiVersion?.let { put("apiVersion", JsonPrimitive(it)) }
            })

            val version = repoService.createVersion(repo.id, chartMeta.version, metadata)
            repoService.addVersionBlob(version.id, digest, "chart", "${chartMeta.name}-${chartMeta.version}.tgz", "application/gzip")

            call.respond(HttpStatusCode.Created, "Chart ${chartMeta.name}-${chartMeta.version} uploaded successfully")
        } finally {
            withContext(Dispatchers.IO) {
                if (!tempFile.delete()) {
                    org.slf4j.LoggerFactory.getLogger("bosca.artifacts.helm.routes.HelmPushChart")
                        .warn("Failed to delete temp file: {}", tempFile.absolutePath)
                }
            }
        }
    }
}

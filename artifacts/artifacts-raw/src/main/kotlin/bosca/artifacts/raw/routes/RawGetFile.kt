package bosca.artifacts.raw.routes

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
 * Downloads a specific file from a raw artifact version by filename.
 */
@RouteController("/raw/{namespace}/{name}/{version}/{filename}", authentication = RouteAuthentication.OPTIONAL)
class RawGetFile(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val name = call.pathParameters["name"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val version = call.pathParameters["version"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val filename = call.pathParameters["filename"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val ns = repoService.getNamespaceByName(namespace)
        if (!rawRequirePermission(call, permissionEvaluator, authenticationContext, namespace, name, version, ArtifactAction.PULL, ns?.public ?: false)) {
            return
        }

        val repo = repoService.findRepository(namespace, name, ArtifactType.RAW)
        if (repo == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val artifactVersion = repoService.findVersion(repo.id, version)
        if (artifactVersion == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val blobs = repoService.getVersionBlobs(artifactVersion.id)
        val fileBlob = blobs.find { it.filename == filename }
        if (fileBlob == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val blob = blobService.get(fileBlob.digest)
        if (blob == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val stream = blobService.getInputStream(fileBlob.digest)

        call.response.header(HttpHeaders.ContentLength, blob.size.toString())
        call.response.header(HttpHeaders.ETag, "\"${fileBlob.digest}\"")
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")

        // Closed whatever happens: a HEAD request is answered without running the streaming block.
        stream.use { input ->
            // No time limit: a large blob on a slow link may take long; a client that stops reading
            // altogether is ended by the streaming response's stall timeout.
            call.respondStreaming(ContentType.Application.OctetStream, HttpStatusCode.OK, timeLimit = null) { output -> output.copyFrom(input) }
        }
    }
}

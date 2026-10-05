package bosca.artifacts.helm.routes

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
 * Downloads a packaged Helm chart tarball by chart name and version.
 *
 * Uses explicit path segments for name and version to avoid ambiguity
 * with chart names that contain dashes (e.g., `bosca-server`).
 */
@RouteController("/helm/{namespace}/charts/{name}/{version}", authentication = RouteAuthentication.OPTIONAL)
class HelmGetChart(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val chartName = call.pathParameters["name"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val chartVersion = call.pathParameters["version"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val ns = repoService.getNamespaceByName(namespace)
        if (!helmRequirePermission(call, permissionEvaluator, authenticationContext, namespace, chartName, chartVersion, ArtifactAction.PULL, ns?.public ?: false)) {
            return
        }

        val repo = repoService.findRepository(namespace, chartName, ArtifactType.HELM)
        if (repo == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val version = repoService.findVersion(repo.id, chartVersion)
        if (version == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val blobs = repoService.getVersionBlobs(version.id)
        val chartBlob = blobs.find { it.role == "chart" }
        if (chartBlob == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val blob = blobService.get(chartBlob.digest)
        if (blob == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val stream = blobService.getInputStream(chartBlob.digest)
        val filename = "$chartName-$chartVersion.tgz"

        call.response.header(HttpHeaders.ContentLength, blob.size.toString())
        call.response.header(HttpHeaders.ETag, "\"${chartBlob.digest}\"")
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")

        // Closed whatever happens: a HEAD request is answered without running the streaming block.
        stream.use { input ->
            // No time limit: a large blob on a slow link may take long; a client that stops reading
            // altogether is ended by the streaming response's stall timeout.
            call.respondStreaming(ContentType.Application.OctetStream, HttpStatusCode.OK, timeLimit = null) { output -> output.copyFrom(input) }
        }
    }
}

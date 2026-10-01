package bosca.artifacts.maven.routes

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
 * Handles Maven artifact downloads, metadata retrieval, and checksum requests
 * within a named repository.
 *
 * The URL structure is `/maven/{repository}/{standard maven path}` where the
 * repository is an explicit namespace identifier and the remaining path follows
 * standard Maven repository layout conventions.
 */
@RouteController("/maven/{repository}/{path...}", authentication = RouteAuthentication.OPTIONAL)
class MavenGet(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val repository = call.pathParameters["repository"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val requestPath = call.pathParameters["path"] ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val coords = parseMavenPath("/$requestPath")
        if (coords == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val ns = repoService.getNamespaceByName(repository)
        if (!permissionEvaluator.evaluate(authenticationContext, "maven", repository, "${coords.groupId}.${coords.artifactId}", coords.version, ArtifactAction.PULL, ns?.public ?: false)) {
            if (authenticationContext.principal() == null) {
                call.response.header(HttpHeaders.WWWAuthenticate, """Basic realm="Bosca Maven Registry"""")
                call.respond(HttpStatusCode.Unauthorized, "")
            } else {
                call.respond(HttpStatusCode.Forbidden, "")
            }
            return
        }

        if (coords.version == null && isMetadataChecksumFile(coords.filename)) {
            handleMavenMetadataChecksum(call, repository, coords, repoService)
            return
        }

        if (isChecksumFile(coords.filename)) {
            handleMavenChecksum(call, repository, coords, repoService, blobService)
            return
        }

        if (coords.filename != null && coords.filename.startsWith("maven-metadata") && coords.filename.endsWith(".xml")) {
            handleMavenMetadata(call, repository, coords, repoService)
            return
        }

        val repo = repoService.findRepository(repository, "${coords.groupId}.${coords.artifactId}", ArtifactType.MAVEN)
        if (repo == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val version = coords.version?.let { repoService.findVersion(repo.id, it) }
        if (version == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val blobs = repoService.getVersionBlobs(version.id)
        val versionBlob = blobs.find { it.filename == coords.filename }
        if (versionBlob == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val blob = blobService.get(versionBlob.digest)
        if (blob == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        // The response sets Content-Type itself; a header set here as well would be sent twice.
        // A stored media type that is not type/subtype falls back rather than failing the download.
        val contentType = versionBlob.mediaType
            ?.takeIf { '/' in it.substringBefore(';') }
            ?.let(ContentType::parse)
            ?: ContentType.Application.OctetStream

        // Opened before the blob's headers are set, so a storage failure answers without them.
        // Nothing between here and use{} can throw.
        val stream = blobService.getInputStream(versionBlob.digest)

        call.response.header(HttpHeaders.ContentLength, blob.size.toString())
        call.response.header(HttpHeaders.ETag, "\"${versionBlob.digest}\"")

        // Closed whatever happens: a HEAD request is answered without running the streaming block.
        stream.use { input ->
            // No time limit: a large blob on a slow link may take long; a client that stops reading
            // altogether is ended by the streaming response's stall timeout.
            call.respondStreaming(contentType, HttpStatusCode.OK, timeLimit = null) { output -> output.copyFrom(input) }
        }
    }
}

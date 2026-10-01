package bosca.artifacts.docker.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.model.UploadSessionPath
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.storage.service.ObjectStorageService

/**
 * Completes a chunked blob upload by verifying the incrementally calculated
 * digest and asking object storage to assemble its existing multipart parts.
 */
@RouteController("/v2/{namespace}/{repo}/blobs/uploads/{uuid}", method = RouteMethod.PUT, authentication = RouteAuthentication.OPTIONAL)
class DockerCompleteBlobUpload(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
    private val objectStorage: ObjectStorageService,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: return call.respond(HttpStatusCode.NotFound)
        val repoName = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.NotFound)
        val uuid = call.pathParameters["uuid"] ?: return call.respond(HttpStatusCode.NotFound)
        if (!dockerRequirePermission(call, permissionEvaluator, authenticationContext, "docker", namespace, repoName, null, ArtifactAction.PUSH)) return

        val digest = call.request.queryParameters["digest"]
        val expectedDigest = digest?.takeIf(::isSha256Digest)
        if (expectedDigest == null) {
            call.respond(HttpStatusCode.BadRequest, dockerError("DIGEST_INVALID", "valid sha256 digest parameter is required"))
            return
        }

        val sessionId = UUID.parse(uuid)
        val session = repoService.getUploadSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, dockerError("BLOB_UPLOAD_UNKNOWN", "upload session not found"))
            return
        }

        // Verify the upload session belongs to the repository resolved from the URL.
        // Uses findRepository (read-only) rather than findOrCreateRepository to avoid
        // creating a namespace/repo as a side effect of a validation check.
        val repo = repoService.findRepository(namespace, repoName, ArtifactType.DOCKER)
        if (repo == null || session.repositoryId != repo.id) {
            call.respond(HttpStatusCode.NotFound, dockerError("BLOB_UPLOAD_UNKNOWN", "upload session not found"))
            return
        }

        val uploadId = session.storageUploadId
        if (uploadId == null) {
            call.respond(HttpStatusCode.NotFound, dockerError("BLOB_UPLOAD_UNKNOWN", "upload session cannot be resumed"))
            return
        }
        val contentLengthHeader = call.request.headers[HttpHeaders.ContentLength]
        val contentLength = contentLengthHeader?.toLongOrNull() ?: 0L
        if (contentLength < 0 || (contentLengthHeader != null && contentLengthHeader.toLongOrNull() == null)) {
            call.respond(HttpStatusCode.LengthRequired)
            return
        }
        val contentRange = call.request.headers[HttpHeaders.ContentRange]
        val finalRangeMatches = contentLength == 0L ||
            (session.byteOffset == 0L && contentRange == null) ||
            uploadRangeMatches(
                contentRange,
                session.byteOffset,
                contentLength,
            )
        if (!finalRangeMatches) {
            call.response.header("Range", currentUploadRange(session.byteOffset))
            call.respond(
                HttpStatusCode.RequestedRangeNotSatisfiable,
                dockerError("BLOB_UPLOAD_INVALID", "final chunk range does not match the current upload offset"),
            )
            return
        }

        val uploadDigest = DockerUploadDigest(session.digestState)
        var partCount = session.chunkCount
        var totalBytes = session.byteOffset
        if (contentLength > 0) {
            val finalPartNumber = partCount + 1
            totalBytes += streamMultipartPart(
                expectedLength = contentLength,
                digest = uploadDigest,
                writeBody = call.request::bodyStreamTo,
            ) { input ->
                objectStorage.uploadMultipartPart(
                    UploadSessionPath(uuid),
                    uploadId,
                    finalPartNumber,
                    input,
                    contentLength,
                )
            }
            partCount++
        }

        if (totalBytes == 0L) {
            repoService.cancelUploadSession(sessionId, partCount)
            call.respond(HttpStatusCode.BadRequest, dockerError("BLOB_UPLOAD_INVALID", "no data uploaded"))
            return
        }

        val computedDigest = "sha256:${uploadDigest.hexDigest()}"
        if (computedDigest != expectedDigest) {
            repoService.cancelUploadSession(sessionId, partCount)
            call.respond(HttpStatusCode.BadRequest, dockerError("DIGEST_INVALID", "provided digest does not match uploaded content"))
            return
        }

        blobService.completeMultipartUpload(
            expectedDigest,
            UploadSessionPath(uuid),
            uploadId,
            partCount,
            totalBytes,
        )
        repoService.completeUploadSession(sessionId)

        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        call.response.header(HttpHeaders.Location, "/v2/$namespace/$repoName/blobs/$expectedDigest")
        call.response.header("Docker-Content-Digest", expectedDigest)
        call.respond(HttpStatusCode.Created)
    }
}

private fun isSha256Digest(value: String): Boolean =
    value.startsWith("sha256:") &&
        value.length == SHA256_DIGEST_LENGTH &&
        value.substringAfter(':').all { it in '0'..'9' || it in 'a'..'f' }

private const val SHA256_DIGEST_LENGTH = 71

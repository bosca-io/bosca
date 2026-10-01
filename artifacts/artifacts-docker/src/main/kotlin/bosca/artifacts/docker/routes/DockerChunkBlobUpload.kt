package bosca.artifacts.docker.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.model.UploadChunkPath
import bosca.artifacts.model.UploadSessionPath
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
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

/** Receives a streaming or fixed-length part for an in-progress blob upload. */
@RouteController("/v2/{namespace}/{repo}/blobs/uploads/{uuid}", method = RouteMethod.PATCH, authentication = RouteAuthentication.OPTIONAL)
class DockerChunkBlobUpload(
    private val repoService: ArtifactRepositoryService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
    private val objectStorage: ObjectStorageService,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val namespace = call.pathParameters["namespace"] ?: return call.respond(HttpStatusCode.NotFound)
        val repoName = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.NotFound)
        val uuid = call.pathParameters["uuid"] ?: return call.respond(HttpStatusCode.NotFound)
        if (!dockerRequirePermission(call, permissionEvaluator, authenticationContext, "docker", namespace, repoName, null, ArtifactAction.PUSH)) return

        val sessionId = UUID.parse(uuid)
        val session = repoService.getUploadSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, dockerError("BLOB_UPLOAD_UNKNOWN", "upload session not found"))
            return
        }

        // Verify the upload session belongs to the repository resolved from the URL
        // to prevent IDOR attacks where a valid session UUID from another repository
        // could be used to hijack an upload. Uses findRepository (read-only) rather
        // than findOrCreateRepository to avoid creating a namespace/repo as a side
        // effect of a validation check.
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
        val contentLength = contentLengthHeader?.toLongOrNull()
        if (contentLengthHeader != null && (contentLength == null || contentLength <= 0)) {
            call.respond(HttpStatusCode.LengthRequired)
            return
        }
        val contentRange = call.request.headers[HttpHeaders.ContentRange]
        if (contentLength != null && contentRange != null &&
            !uploadRangeMatches(contentRange, session.byteOffset, contentLength)
        ) {
            call.response.header("Range", currentUploadRange(session.byteOffset))
            call.respond(
                HttpStatusCode.RequestedRangeNotSatisfiable,
                dockerError("BLOB_UPLOAD_INVALID", "chunk range does not match the current upload offset"),
            )
            return
        }

        val digest = DockerUploadDigest(session.digestState)
        val partNumber = session.chunkCount + 1
        val uploadPath = UploadSessionPath(uuid)
        val bodySize = if (contentLength != null) {
            streamMultipartPart(
                expectedLength = contentLength,
                digest = digest,
                writeBody = call.request::bodyStreamTo,
            ) { input ->
                objectStorage.uploadMultipartPart(uploadPath, uploadId, partNumber, input, contentLength)
            }
        } else {
            val stagingPath = UploadChunkPath(uuid, session.chunkCount)
            try {
                val stagedSize = streamMultipartPart(
                    expectedLength = null,
                    digest = digest,
                    writeBody = call.request::bodyStreamTo,
                ) { input ->
                    objectStorage.setInputStream(stagingPath, input)
                }
                if (stagedSize <= 0) {
                    call.respond(HttpStatusCode.BadRequest, dockerError("BLOB_UPLOAD_INVALID", "no data uploaded"))
                    return
                }
                if (contentRange != null && !uploadRangeMatches(contentRange, session.byteOffset, stagedSize)) {
                    call.response.header("Range", currentUploadRange(session.byteOffset))
                    call.respond(
                        HttpStatusCode.RequestedRangeNotSatisfiable,
                        dockerError("BLOB_UPLOAD_INVALID", "chunk range does not match the current upload offset"),
                    )
                    return
                }
                val copiedSize = objectStorage.copyToMultipartPart(
                    stagingPath,
                    uploadPath,
                    uploadId,
                    partNumber,
                    stagedSize,
                )
                check(copiedSize == stagedSize) {
                    "object storage copied $copiedSize bytes for a $stagedSize-byte staged object"
                }
                stagedSize
            } finally {
                objectStorage.delete(stagingPath)
            }
        }

        val newOffset = session.byteOffset + bodySize
        repoService.updateUploadSessionOffset(sessionId, newOffset, digest.encodedState())

        call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
        call.response.header(HttpHeaders.Location, "/v2/$namespace/$repoName/blobs/uploads/$uuid")
        call.response.header("Docker-Upload-UUID", uuid)
        call.response.header("Range", "0-${newOffset - 1}")
        call.respond(HttpStatusCode.Accepted)
    }
}

internal fun currentUploadRange(byteOffset: Long): String =
    if (byteOffset == 0L) "0-0" else "0-${byteOffset - 1}"

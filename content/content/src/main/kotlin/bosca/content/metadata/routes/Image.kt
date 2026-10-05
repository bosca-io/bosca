package bosca.content.metadata.routes

import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.connection
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import bosca.server.ContentType
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer

@RouteController("/content/image/{id}")
class Image(
    private val slugService: SlugService,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val objectService: ObjectStorageService
) : Route<Unit>() {

    override fun serializer(): KSerializer<Unit>? = null

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        try {
            val id: String = call.pathParameters["id"] ?: error("missing id")
            val uuidId = try {
                UUID.parse(id.split(".").first())
            } catch (_: Exception) {
                null
            }
            val metadata = if (uuidId != null) {
                metadataService.getById(uuidId) ?: throw NoSuchElementException("Metadata not found")
            } else {
                val slug = id.split(".").first()
                val metadataSlug = slugService.get(slug) ?: throw NoSuchElementException("Metadata not found")
                metadataService.getById(metadataSlug.metadataId ?: error("missing id")) ?: throw NoSuchElementException("Metadata not found for slug $slug")
            }

            val supplementary = if (call.request.queryParameters.contains("supplementaryId")) {
                val supplementaryId = call.request.queryParameters["supplementaryId"] ?: error("missing supplementaryId")
                val supplementary = metadataService.getSupplementaryById(UUID.parse(supplementaryId)) ?: throw NoSuchElementException("Supplementary not found")
                metadataPermissionEvaluator.verifySupplementaryAllowed(authenticationContext, metadata, PermissionAction.VIEW)
                supplementary
            } else if (call.request.queryParameters.contains("key")) {
                val key = call.request.queryParameters["key"] ?: error("missing key")
                val requested = metadataService.getSupplementaryByMetadataAndKey(metadata.id, key)
                requested?.let {
                    metadataPermissionEvaluator.verifySupplementaryAllowed(authenticationContext, metadata, PermissionAction.VIEW)
                    it
                } ?: run {
                    metadataPermissionEvaluator.verifyContentAllowed(authenticationContext, metadata, PermissionAction.VIEW)
                    null
                }
            } else {
                metadataPermissionEvaluator.verifyContentAllowed(authenticationContext, metadata, PermissionAction.VIEW)
                null
            }

            connection().release()

            if (call.respondNotModifiedIfMatches(metadata.etag)) return

            val path = objectService.getPath(metadata, supplementary?.id)
            objectService.getInputStream(path).use { file ->
                // Only once the object is open: a 404 must not carry its tag.
                call.setEntityTagHeaders(metadata.etag)
                // No time limit: a large image on a slow link may take long; a client that stops
                // reading altogether is ended by the streaming response's stall timeout.
                call.respondStreaming(
                    contentType = ContentType.parse(supplementary?.contentType ?: metadata.contentType),
                    status = HttpStatusCode.OK,
                    timeLimit = null,
                ) { stream ->
                    stream.copyFrom(file)
                }
            }
        } catch (e: ObjectNotFoundException) {
            // A missing object is the client's 404. A lazy backend (GCS) can report it mid-stream,
            // once the response is committed; then it can only fail the response.
            if (call.response.isCommitted) throw e
            call.respond(HttpStatusCode.NotFound, "File not found")
        }
    }
}

package bosca.content.metadata.routes

import bosca.content.metadata.events.METADATA_UPLOAD_PROGRESS_CHANNEL
import bosca.content.metadata.events.UploadProgress
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.pubsub.PubSubService
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.upload
import bosca.server.HttpStatusCode
import bosca.server.content.*
import bosca.server.BoscaApplication
import bosca.server.ServerCall

@RouteController("/api/v1/content/metadata/upload", method = RouteMethod.POST)
class Upload(
    private val application: BoscaApplication,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val objectService: ObjectStorageService,
    private val pubSubService: PubSubService
) : Route<HttpStatusCode>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HttpStatusCode {
        val id = UUID.parse(call.request.queryParameters["id"] ?: error("missing id"))
        val metadata = metadataService.getById(id) ?: error("Metadata not found")
        metadataPermissionEvaluator.verifyContentAllowed(authenticationContext, metadata, PermissionAction.EDIT)
        val multipart = call.receiveMultipart()
        multipart.forEachPart { p ->
            application.log.info("Including part: ${p.name}")
            try {
                if (p is PartData.FileItem) {
                    application.log.info("uploading metadata")
                    val contentType = p.contentType ?: metadata.contentType
                    val size = objectService.upload(metadata, null, p) { bytesRead ->
                        pubSubService.publish(
                            METADATA_UPLOAD_PROGRESS_CHANNEL,
                            UploadProgress.serializer(),
                            UploadProgress(metadata.id, bytesRead, 0)
                        )
                    }
                    metadataService.setUploaded(metadata.id, contentType, size)
                    application.log.info("metadata uploaded")
                }
            } finally {
                p.dispose()
            }
        }
        return HttpStatusCode.Created
    }
}

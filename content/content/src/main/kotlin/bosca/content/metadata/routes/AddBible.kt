package bosca.content.metadata.routes

import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.MetadataService
import bosca.db.transaction
import bosca.jobs.BibleProcessJob
import bosca.jobs.enqueue
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.upload
import bosca.server.HttpStatusCode
import bosca.server.content.*
import bosca.server.BoscaApplication
import bosca.server.ServerCall

@RouteController("/api/v1/content/metadata/bibles", method = RouteMethod.POST)
class AddBible(
    private val application: BoscaApplication,
    private val groups: GroupEvaluator,
    private val slugService: SlugService,
    private val collectionService: CollectionService,
    private val metadataService: MetadataService,
    private val objectService: ObjectStorageService
) : Route<HttpStatusCode>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HttpStatusCode {
        application.log.info("Received request to add bible")
        groups.verifyHasAdminGroup(authenticationContext)
        val principalId = authenticationContext.principal()?.id ?: error("missing principal")
        val multipart = call.receiveMultipart()
        multipart.forEachPart { p ->
            application.log.info("Including part: ${p.name}")
            try {
                if (p is PartData.FileItem) {
                    application.log.info("adding metadata")
                    val parentSlug = slugService.get("bibles")?.collectionId ?: error("No parent slug found")
                    val biblesCollection = collectionService.getById(parentSlug)
                    val metadata = transaction {
                        metadataService.add(
                            biblesCollection,
                            null,
                            MetadataInput(
                                parentCollectionId = parentSlug,
                                name = p.originalFileName ?: "",
                                languageTag = "und",
                                contentType = "bosca/v-bible",
                            ),
                        )
                    }

                    application.log.info("uploading metadata")
                    val size = objectService.upload(metadata, null, p)
                    metadataService.setUploaded(metadata.id, "bosca/v-bible", size)
                    BibleProcessJob(
                        id = metadata.id,
                        version = metadata.version,
                        initializeMetadata = true,
                        publish = true,
                        principalId = principalId,
                    ).enqueue()
                    application.log.info("queued Bible processing for {}", metadata.id)
                }
            } finally {
                p.dispose()
            }
        }
        return HttpStatusCode.Created
    }
}

package bosca.content.collection.jobs

import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.jobs.MetadataIndexJob
import bosca.content.metadata.jobs.enqueue
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.db.transaction
import bosca.events.eventManager
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import org.slf4j.LoggerFactory

@JobDefinition(CollectionMetadataItemAddedJob::class, JobQueueNames.contentJobQueue, "collection-metadata-item-added")
class CollectionMetadataItemAddedExecutor(
    private val collectionService: CollectionService,
    metadataService: MetadataService,
) : CollectionMetadataItemSyncExecutor<CollectionMetadataItemAddedJob>(metadataService, CollectionMetadataItemAddedJob.serializer()) {

    override suspend fun syncVariantCollections(collectionId: UUID, metadata: Metadata) {
        if (!metadata.syncVariantCollections) {
            metadataService.markCollaborationCollectionsDirty(metadata.id)
            return
        }
        val variants = getVariants(metadata)
        val added = collectionService.getCollectionMetadataItem(collectionId, metadata.id)
        transaction {
            eventManager().disabled {
                variants.filter { it.syncVariantCollections }.forEach { variant ->
                    transaction {
                        try {
                            collectionService.addMetadataItem(collectionId, variant.id, added.attributes)
                        } catch (e: Exception) {
                            log.error("error: failed to add metadata item to collection", e)
                        }
                        metadataService.markCollaborationCollectionsDirty(variant.id)
                        MetadataIndexJob(
                            id = variant.id,
                            version = variant.version,
                        ).enqueue()
                    }
                }
                metadataService.markCollaborationCollectionsDirty(metadata.id)
                MetadataIndexJob(
                    id = metadata.id,
                    version = metadata.version,
                ).enqueue()
            }
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(CollectionMetadataItemAddedExecutor::class.java)
    }
}

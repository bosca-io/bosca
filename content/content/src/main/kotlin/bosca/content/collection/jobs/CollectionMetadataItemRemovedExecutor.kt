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

@JobDefinition(CollectionMetadataItemRemovedJob::class, JobQueueNames.contentJobQueue, "collection-metadata-item-removed")
class CollectionMetadataItemRemovedExecutor(
    private val collectionService: CollectionService,
    metadataService: MetadataService
) : CollectionMetadataItemSyncExecutor<CollectionMetadataItemRemovedJob>(metadataService, CollectionMetadataItemRemovedJob.serializer()) {

    override suspend fun syncVariantCollections(collectionId: UUID, metadata: Metadata) {
        if (!metadata.syncVariantCollections) {
            metadataService.markCollaborationCollectionsDirty(metadata.id)
            return
        }
        val variants = getVariants(metadata)
        if (variants.isEmpty()) return
        val collection = collectionService.getById(collectionId)
        transaction {
            eventManager().disabled {
                variants.filter { it.syncVariantCollections }.forEach { variant ->
                    collectionService.removeMetadataItem(collectionId, variant.id)
                    collection?.let { metadataService.markCollaborationCollectionsDirty(variant.id) }
                    MetadataIndexJob(
                        id = variant.id,
                        version = variant.version,
                    ).enqueue()
                }
                metadataService.markCollaborationCollectionsDirty(metadata.id)
                MetadataIndexJob(
                    id = metadata.id,
                    version = metadata.version,
                ).enqueue()
            }
        }
    }
}
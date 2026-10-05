package bosca.content.collection.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.KSerializer

interface CollectionMetadataItemSyncJob {

    val id: UUID?

    val metadataId: UUID?
}

abstract class CollectionMetadataItemSyncExecutor<T : CollectionMetadataItemSyncJob>(
    protected val metadataService: MetadataService,
    serializer: KSerializer<T>
) : AbstractJobExecutor<T>(serializer) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val collectionId = job.id ?: error("Missing collection id")
        val metadata = metadataService.getById(job.metadataId ?: error("Missing metadata id")) ?: error("Missing metadata")
        if (metadata.syncVariantCollections) {
            syncVariantCollections(collectionId, metadata)
        }
    }

    protected suspend fun getVariants(metadata: Metadata): List<Metadata> {
        val variants = if (metadata.parentId != null) {
            metadataService.getByParentId(metadata.parentId ?: error("Missing parent id"))
        } else {
            metadataService.getByParentId(metadata.id)
        }
        return variants.filter { it.id != metadata.id }
    }

    protected abstract suspend fun syncVariantCollections(collectionId: UUID, metadata: Metadata)
}
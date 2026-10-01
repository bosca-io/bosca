package bosca.content.metadata.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.service.MetadataService
import bosca.content.model.ContentRelationship
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.KSerializer

interface MetadataSyncJob {

    val id: UUID?

    val relationship: ContentRelationship?
}

abstract class MetadataSyncExecutor<T : MetadataSyncJob>(
    protected val metadataService: MetadataService,
    serializer: KSerializer<T>
) : AbstractJobExecutor<T>(serializer) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val metadata = metadataService.getById(job.id ?: error("Missing metadata id")) ?: error("Missing metadata")
        if (metadata.syncVariantCollections) {
            job.relationship?.let { syncVariantRelationship(metadata, it) }
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

    protected abstract suspend fun syncVariantRelationship(metadata: Metadata, relationship: ContentRelationship)
}
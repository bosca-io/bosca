package bosca.content.collection.jobs

import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.content.image.service.ImageService
import bosca.content.metadata.service.MetadataService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.FailException

@JobDefinition(CollectionProcessContentJob::class, JobQueueNames.contentJobQueue, "collection-process-content")
class CollectionProcessContentExecutor(
    private val collectionService: CollectionService,
    private val metadataService: MetadataService,
    private val imageService: ImageService
) : AbstractJobExecutor<CollectionProcessContentJob>(CollectionProcessContentJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val collection = collectionService.getById(job.id) ?: throw FailException("Collection not found: ${job.id}")
        val hasImageRelationships = collectionService.getMetadataRelationships(collection.id).any {
            val metadataRel = metadataService.getById(it.metadataId)
            if (metadataRel == null) {
                return@any false
            } else {
                return@any metadataRel.uploaded != null
            }
        }
        val hasVariantRelationships = collectionService.getLanguageVariants(collection.id).any {
            collectionService.getMetadataRelationships(it.id, it.languageTag).any {
                val metadataRel = metadataService.getById(it.metadataId)
                if (metadataRel == null) {
                    return@any false
                } else {
                    return@any metadataRel.uploaded != null
                }
            }
        }
        if (hasImageRelationships || hasVariantRelationships) {
            imageService.optimize(collection)
        }
    }
}

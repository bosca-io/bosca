package bosca.content.collection.jobs

import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

@JobDefinition(CollectionLanguageVariantMetadataRelationshipMergedJob::class, JobQueueNames.contentJobQueue, "collection-language-variant-metadata-relationship-merged")
class CollectionLanguageVariantMetadataRelationshipMergedExecutor(
    private val collectionService: CollectionService,
) : AbstractJobExecutor<CollectionLanguageVariantMetadataRelationshipMergedJob>(CollectionLanguageVariantMetadataRelationshipMergedJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val relationship = job.relationship ?: return
        collectionService.markCollaborationRelationshipsDirty(relationship.collectionId, relationship.languageTag)
    }
}

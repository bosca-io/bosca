package bosca.content.collection.jobs

import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

@JobDefinition(CollectionLanguageVariantMetadataRelationshipAddedJob::class, JobQueueNames.contentJobQueue, "collection-language-variant-metadata-relationship-added")
class CollectionLanguageVariantMetadataRelationshipAddedExecutor(
    private val collectionService: CollectionService,
) : AbstractJobExecutor<CollectionLanguageVariantMetadataRelationshipAddedJob>(CollectionLanguageVariantMetadataRelationshipAddedJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val relationship = job.relationship ?: return
        collectionService.markCollaborationRelationshipsDirty(relationship.collectionId, relationship.languageTag)
    }
}

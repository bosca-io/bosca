package bosca.content.collection.jobs

import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

@JobDefinition(CollectionLanguageVariantMetadataRelationshipRemovedJob::class, JobQueueNames.contentJobQueue, "collection-language-variant-metadata-relationship-removed")
class CollectionLanguageVariantMetadataRelationshipRemovedExecutor(
    private val collectionService: CollectionService,
) : AbstractJobExecutor<CollectionLanguageVariantMetadataRelationshipRemovedJob>(CollectionLanguageVariantMetadataRelationshipRemovedJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val relationship = job.relationship ?: return
        collectionService.markCollaborationRelationshipsDirty(relationship.collectionId, relationship.languageTag)
    }
}

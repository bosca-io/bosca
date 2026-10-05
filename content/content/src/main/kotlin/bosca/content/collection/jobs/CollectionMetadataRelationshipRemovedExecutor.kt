package bosca.content.collection.jobs

import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

@JobDefinition(CollectionMetadataRelationshipRemovedJob::class, JobQueueNames.contentJobQueue, "collection-metadata-relationship-removed")
class CollectionMetadataRelationshipRemovedExecutor(
    private val collectionService: CollectionService,
) : AbstractJobExecutor<CollectionMetadataRelationshipRemovedJob>(CollectionMetadataRelationshipRemovedJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        collectionService.markCollaborationRelationshipsDirty(job.id ?: error("missing collection id"), null)
    }
}

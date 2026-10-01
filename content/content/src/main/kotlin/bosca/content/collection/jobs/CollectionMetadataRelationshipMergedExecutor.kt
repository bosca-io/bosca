package bosca.content.collection.jobs

import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

@JobDefinition(CollectionMetadataRelationshipMergedJob::class, JobQueueNames.contentJobQueue, "collection-metadata-relationship-merged")
class CollectionMetadataRelationshipMergedExecutor(
    private val collectionService: CollectionService,
) : AbstractJobExecutor<CollectionMetadataRelationshipMergedJob>(CollectionMetadataRelationshipMergedJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        collectionService.markCollaborationRelationshipsDirty(job.id ?: error("missing collection id"), null)
    }
}

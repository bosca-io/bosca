package bosca.content.collection.jobs

import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

@JobDefinition(CollectionMetadataRelationshipAddedJob::class, JobQueueNames.contentJobQueue, "collection-metadata-relationship-added")
class CollectionMetadataRelationshipAddedExecutor(
    private val collectionService: CollectionService,
) : AbstractJobExecutor<CollectionMetadataRelationshipAddedJob>(CollectionMetadataRelationshipAddedJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        collectionService.markCollaborationRelationshipsDirty(job.id ?: error("missing collection id"), null)
    }
}

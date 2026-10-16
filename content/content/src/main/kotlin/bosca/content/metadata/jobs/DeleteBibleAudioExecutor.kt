package bosca.content.metadata.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.service.getBibleAudioPath
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.storage.service.ObjectStorageService

@JobDefinition(definition = DeleteBibleAudioJob::class, queue = JobQueueNames.contentJobQueue,
    name = "delete-bible-audio", displayName = "Delete Bible Audio")
class DeleteBibleAudioExecutor(private val storage: ObjectStorageService) :
    AbstractJobExecutor<DeleteBibleAudioJob>(DeleteBibleAudioJob.serializer()) {
    override suspend fun execute() { storage.delete(getBibleAudioPath(getJobDefinition().id)) }
}

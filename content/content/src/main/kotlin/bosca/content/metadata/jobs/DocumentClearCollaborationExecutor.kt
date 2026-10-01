package bosca.content.metadata.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.service.DocumentService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

@JobDefinition(DocumentClearCollaborationJob::class, JobQueueNames.contentJobQueue, "clear-document-collaboration")
class DocumentClearCollaborationExecutor(
    private val documentService: DocumentService
) : AbstractJobExecutor<DocumentClearCollaborationJob>(DocumentClearCollaborationJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        documentService.removeCollaboration(job.id ?: error("id missing"), job.version ?: error("version missing"))
    }
}

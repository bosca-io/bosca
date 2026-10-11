package bosca.content.metadata.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.service.BibleService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

@JobDefinition(
    definition = LimitBibleChaptersJob::class,
    queue = JobQueueNames.contentJobQueue,
    name = "limit-bible-chapters",
    displayName = "Limit Bible Chapters",
)
class LimitBibleChaptersExecutor(
    private val bibles: BibleService,
) : AbstractJobExecutor<LimitBibleChaptersJob>(LimitBibleChaptersJob.serializer()) {
    override suspend fun execute() {
        val job = getJobDefinition()
        bibles.limitChapterContent(job.provider, job.providerBibleId)
    }
}

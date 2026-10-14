package bosca.bible.providers.jobs

import bosca.content.metadata.service.BibleService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.configuration.JobQueueNames

/** Removes expired responses independently of provider credentials or configuration availability. */
@JobDefinition(
    definition = DeleteExpiredBibleChaptersJob::class,
    queue = JobQueueNames.commonJobQueue,
    name = "delete-expired-bible-chapters",
    displayName = "Delete Expired Bible Chapters",
)
class DeleteExpiredBibleChaptersExecutor(
    private val bibles: BibleService,
) : AbstractJobExecutor<DeleteExpiredBibleChaptersJob>(DeleteExpiredBibleChaptersJob.serializer()) {
    override suspend fun execute() {
        bibles.removeExpiredChapterContent()
    }
}

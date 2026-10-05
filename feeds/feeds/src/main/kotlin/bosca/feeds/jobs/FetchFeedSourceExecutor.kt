package bosca.feeds.jobs

import bosca.feeds.configuration.FeedsJobQueueNames
import bosca.feeds.service.FeedFetchService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

/** Runs one source fetch on the feeds queue. The scheduler enqueues this per source. */
@JobDefinition(
    definition = FetchFeedSourceJob::class,
    queue = FeedsJobQueueNames.feedsJobQueue,
    name = FetchFeedSourceExecutor.NAME,
    displayName = "Feeds: Fetch Source",
)
class FetchFeedSourceExecutor(
    private val feedFetchService: FeedFetchService,
) : AbstractJobExecutor<FetchFeedSourceJob>(FetchFeedSourceJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        feedFetchService.fetch(job.feedSourceId, job.force)
    }

    companion object {
        const val NAME = "feeds-fetch-source"
    }
}

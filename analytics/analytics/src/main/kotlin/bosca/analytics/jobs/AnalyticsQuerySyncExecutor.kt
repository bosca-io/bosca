package bosca.analytics.jobs

import bosca.analytics.configuration.JobQueueNames
import bosca.analytics.service.AnalyticsQueryGitSyncService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

/**
 * Background executor for [AnalyticsQuerySyncJob]. Delegates to
 * [AnalyticsQueryGitSyncService.onPushEvent] which computes the affected
 * queries via the git diff and writes the new SQL into each one. The
 * JobQueue's per-job distributed lock ensures only one worker processes
 * a given job, so duplicate enqueues from multiple PubSub subscribers
 * are at worst wasted work, never racing writes.
 */
@JobDefinition(
    definition = AnalyticsQuerySyncJob::class,
    queue = JobQueueNames.analyticsJobQueue,
    name = "query-sync",
)
class AnalyticsQuerySyncExecutor(
    private val syncService: AnalyticsQueryGitSyncService,
) : AbstractJobExecutor<AnalyticsQuerySyncJob>(AnalyticsQuerySyncJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        syncService.onPushEvent(
            repositoryId = job.repositoryId,
            ref = job.ref,
            beforeSha = job.beforeSha,
            afterSha = job.afterSha,
        )
    }
}

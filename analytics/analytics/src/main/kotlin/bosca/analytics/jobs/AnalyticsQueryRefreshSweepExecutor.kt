package bosca.analytics.jobs

import bosca.analytics.configuration.JobQueueNames
import bosca.analytics.service.AnalyticsQueryResultCacheService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Background executor for [AnalyticsQueryRefreshSweepJob]. Finds queries with at
 * least one cached parameter combination whose last refresh is older than the
 * query's refresh interval and enqueues an [AnalyticsQueryRefreshJob] for each,
 * so individual queries refresh independently with their own retry semantics.
 *
 * Also prunes bookkeeping entries for combinations nobody has requested recently,
 * so abandoned parameter sets stop consuming background executions.
 *
 * Because refresh intervals are evaluated here, the effective floor for a query's
 * refresh interval is this sweep's scheduled cadence.
 */
@JobDefinition(
    definition = AnalyticsQueryRefreshSweepJob::class,
    queue = JobQueueNames.analyticsJobQueue,
    name = "query-refresh-sweep",
)
class AnalyticsQueryRefreshSweepExecutor(
    private val resultCache: AnalyticsQueryResultCacheService,
) : AbstractJobExecutor<AnalyticsQueryRefreshSweepJob>(AnalyticsQueryRefreshSweepJob.serializer()) {

    override suspend fun execute() {
        resultCache.pruneIdleEntries()
        val queryIds = resultCache.getQueryIdsDueForRefresh()
        if (queryIds.isEmpty()) return
        for (queryId in queryIds) {
            AnalyticsQueryRefreshJob(queryId = queryId, onlyStale = true).enqueue()
        }
        log.info("Enqueued refresh jobs for {} analytics queries", queryIds.size)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AnalyticsQueryRefreshSweepExecutor::class.java)
    }
}

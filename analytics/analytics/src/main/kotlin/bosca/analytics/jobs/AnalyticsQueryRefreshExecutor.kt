package bosca.analytics.jobs

import bosca.analytics.configuration.JobQueueNames
import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryResultCacheService
import bosca.lock.DistributedLockFactory
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Background executor for [AnalyticsQueryRefreshJob]. Re-executes each tracked
 * parameter combination of the query against the analytics store and replaces
 * its cached records. Combinations are refreshed independently — one failing
 * combination is logged and skipped so the rest still refresh; it is retried
 * on the next refresh cycle since its `last_refreshed_at` stays stale.
 *
 * If the query was deleted between enqueue and execution, its bookkeeping
 * entries are already gone (cascade delete) and this is a no-op.
 */
@JobDefinition(
    definition = AnalyticsQueryRefreshJob::class,
    queue = JobQueueNames.analyticsJobQueue,
    name = "query-refresh",
)
class AnalyticsQueryRefreshExecutor(
    private val resultCache: AnalyticsQueryResultCacheService,
    private val executionService: AnalyticsQueryExecutionService,
    private val json: Json,
    private val distributedLockFactory: DistributedLockFactory,
) : AbstractJobExecutor<AnalyticsQueryRefreshJob>(AnalyticsQueryRefreshJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val lock = distributedLockFactory.create("analytics-query-refresh:${job.queryId}")
        if (!lock.tryAcquire(REFRESH_LOCK_TTL_MILLIS)) {
            log.debug("Skipping duplicate analytics query refresh job for {}", job.queryId)
            return
        }
        try {
            coroutineScope {
                val renewer = launch {
                    while (true) {
                        delay(REFRESH_LOCK_RENEW_MILLIS.milliseconds)
                        check(lock.renew(REFRESH_LOCK_TTL_MILLIS)) {
                            "Lost distributed refresh lock for analytics query ${job.queryId}"
                        }
                    }
                }
                try {
                    val entries = resultCache.getRefreshableEntries(job.queryId, job.onlyStale)
                    if (entries.isEmpty()) return@coroutineScope
                    var refreshed = 0
                    for (entry in entries) {
                        try {
                            val parameters = json.decodeFromJsonElement(
                                ListSerializer(AnalyticsQueryExecutionParameterInput.serializer()),
                                entry.parameters,
                            )
                            executionService.refresh(job.queryId, parameters)
                            refreshed++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.error(
                                "Failed to refresh analytics query {} for parameter combination {}",
                                job.queryId,
                                entry.parametersHash,
                                e,
                            )
                        }
                    }
                    log.info(
                        "Refreshed {}/{} cached parameter combinations for analytics query {}",
                        refreshed,
                        entries.size,
                        job.queryId,
                    )
                } finally {
                    renewer.cancelAndJoin()
                }
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    if (!lock.release()) {
                        log.warn("Analytics query refresh lock was no longer held for {}", job.queryId)
                    }
                } catch (e: Exception) {
                    // Refresh work has already completed (or its original failure is
                    // already propagating). The TTL remains the recovery boundary.
                    log.error("Failed to release analytics query refresh lock for {}", job.queryId, e)
                }
            }
        }
    }

    companion object {
        internal const val REFRESH_LOCK_TTL_MILLIS = 300_000L
        internal const val REFRESH_LOCK_RENEW_MILLIS = 60_000L

        private val log = LoggerFactory.getLogger(AnalyticsQueryRefreshExecutor::class.java)
    }
}

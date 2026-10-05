package bosca.scheduler.jobs

import bosca.queue.annotations.JobDefinition
import bosca.scheduler.service.SchedulerService
import bosca.serialization.OffsetDateTime
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.configuration.JobQueueNames
import org.slf4j.LoggerFactory

/** Executes the durable periodic cleanup of completed scheduler job history. */
@JobDefinition(
    definition = PurgeJobHistoryJob::class,
    queue = JobQueueNames.commonJobQueue,
    name = "purge-job-history",
    displayName = "Purge Job History",
)
class PurgeJobHistoryExecutor(
    private val schedulerService: SchedulerService,
) : AbstractJobExecutor<PurgeJobHistoryJob>(PurgeJobHistoryJob.serializer()) {
    override suspend fun execute() {
        val retentionDays = getJobDefinition().retentionDays
        val cutoff = OffsetDateTime.now().minusDays(retentionDays.toLong())
        val deleted = schedulerService.purgeHistoryBefore(cutoff)
        log.info("Purged {} completed job-history records older than {} days", deleted, retentionDays)
    }

    companion object {
        private val log = LoggerFactory.getLogger(PurgeJobHistoryExecutor::class.java)
    }
}

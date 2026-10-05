package bosca.scripting.jobs

import bosca.queue.annotations.JobDefinition
import bosca.scripting.configuration.JobQueueNames
import bosca.scripting.repository.ScriptRepository
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Permanently removes soft-deleted ephemeral scripts that have exceeded the configured
 * retention period. This prevents the database from accumulating stale diagnostic data
 * indefinitely while still allowing recent deletions to be inspected.
 */
@JobDefinition(
    definition = PurgeEphemeralScriptsJob::class,
    queue = JobQueueNames.scriptingJobQueue,
    name = "purge-ephemeral-scripts"
)
class PurgeEphemeralScriptsExecutor(
    private val repository: ScriptRepository
) : AbstractJobExecutor<PurgeEphemeralScriptsJob>(PurgeEphemeralScriptsJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(job.retentionDays.toLong())
        log.info("Purging ephemeral scripts soft-deleted before {} (retention: {} days)", cutoff, job.retentionDays)
        repository.purgeDeletedBefore(cutoff)
        log.info("Purge of ephemeral scripts completed")
    }

    companion object {
        private val log = LoggerFactory.getLogger(PurgeEphemeralScriptsExecutor::class.java)
    }
}

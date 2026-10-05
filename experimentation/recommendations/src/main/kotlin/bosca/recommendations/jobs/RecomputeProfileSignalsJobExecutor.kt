package bosca.recommendations.jobs

import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.recommendations.configuration.JobQueueNames
import bosca.recommendations.service.ProfileSignalComputeService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Executes a [RecomputeProfileSignalsJob]: recomputes + re-caches the Personalization Signals for every
 * attribute drawn from the changed definition's source. Enqueued when a definition is
 * added/edited so existing attributes reflect the new expression/roles.
 */
@JobDefinition(RecomputeProfileSignalsJob::class, JobQueueNames.recommendationsJobQueue, "recompute-profile-signals")
class RecomputeProfileSignalsJobExecutor : AbstractJobExecutor<RecomputeProfileSignalsJob>(
    RecomputeProfileSignalsJob.serializer()
) {
    override suspend fun execute() {
        val job = getJobDefinition()
        log.info("Recomputing personalization signals for {} {}", job.sourceType, job.sourceId)
        provide<ProfileSignalComputeService>().recomputeForSource(job.sourceType, job.sourceId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(RecomputeProfileSignalsJobExecutor::class.java)
    }
}

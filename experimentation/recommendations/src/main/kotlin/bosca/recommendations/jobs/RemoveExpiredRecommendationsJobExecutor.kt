package bosca.recommendations.jobs

import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.recommendations.configuration.JobQueueNames
import bosca.recommendations.service.RecommendationService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

@JobDefinition(RemoveExpiredRecommendationsJob::class, JobQueueNames.recommendationsJobQueue, "remove-expired-recommendations")
class RemoveExpiredRecommendationsJobExecutor : AbstractJobExecutor<RemoveExpiredRecommendationsJob>(
    RemoveExpiredRecommendationsJob.serializer()
) {
    override suspend fun execute() {
        val recommendationService: RecommendationService = provide()
        val count = recommendationService.removeExpired()
        log.info("Removed $count expired recommendations")
    }

    companion object {
        private val log = LoggerFactory.getLogger(RemoveExpiredRecommendationsJobExecutor::class.java)
    }
}

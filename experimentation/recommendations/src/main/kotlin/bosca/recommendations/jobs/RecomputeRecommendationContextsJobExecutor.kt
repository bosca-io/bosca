package bosca.recommendations.jobs

import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.recommendations.configuration.JobQueueNames
import bosca.recommendations.service.RecommendationContextService
import bosca.sharedqueue.jobs.AbstractJobExecutor

/** Runs the bulk content reclassification requested by a context definition change. */
@JobDefinition(
    RecomputeRecommendationContextsJob::class,
    JobQueueNames.recommendationsJobQueue,
    "recompute-recommendation-contexts",
)
class RecomputeRecommendationContextsJobExecutor : AbstractJobExecutor<RecomputeRecommendationContextsJob>(
    RecomputeRecommendationContextsJob.serializer(),
) {
    override suspend fun getLockId(): String = RECOMPUTE_LOCK_ID

    override suspend fun execute() {
        provide<RecommendationContextService>().recompute()
        if (getJobDefinition().trainModels) TrainModelJob().enqueue()
    }

    private companion object {
        const val RECOMPUTE_LOCK_ID = "recommendation-context-recompute"
    }
}

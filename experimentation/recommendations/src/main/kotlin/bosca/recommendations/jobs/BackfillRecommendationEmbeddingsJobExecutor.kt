package bosca.recommendations.jobs

import bosca.content.embedding.service.EmbeddingService
import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.recommendations.configuration.JobQueueNames
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/** Backfills semantic content embeddings and retrains the recommendation models when data changed. */
@JobDefinition(
    BackfillRecommendationEmbeddingsJob::class,
    JobQueueNames.recommendationsJobQueue,
    "backfill-recommendation-embeddings",
)
class BackfillRecommendationEmbeddingsJobExecutor : AbstractJobExecutor<BackfillRecommendationEmbeddingsJob>(
    BackfillRecommendationEmbeddingsJob.serializer(),
) {
    override suspend fun getLockId(): String = BACKFILL_LOCK_ID

    override suspend fun execute() {
        val definition = getJobDefinition()
        val count = provide<EmbeddingService>().backfill(
            overwriteExisting = definition.overwriteExisting,
            batchSize = definition.batchSize,
        )
        log.info("Backfilled {} recommendation content embeddings", count)
        if (count > 0) TrainModelJob().enqueue()
    }

    private companion object {
        const val BACKFILL_LOCK_ID = "recommendation-embedding-backfill"
        val log = LoggerFactory.getLogger(BackfillRecommendationEmbeddingsJobExecutor::class.java)
    }
}

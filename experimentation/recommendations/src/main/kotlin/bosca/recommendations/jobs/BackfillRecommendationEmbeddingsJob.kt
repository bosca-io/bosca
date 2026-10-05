package bosca.recommendations.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/**
 * Durable request to fill missing recommendation embeddings or refresh every existing eligible embedding.
 *
 * @property overwriteExisting false to fill gaps only, true to recompute all ready, recommendable content
 * @property batchSize number of metadata entries inspected per page
 */
@Serializable
data class BackfillRecommendationEmbeddingsJob(
    val overwriteExisting: Boolean = false,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
) : IJobDefinition {
    companion object {
        const val DEFAULT_BATCH_SIZE = 100
    }
}

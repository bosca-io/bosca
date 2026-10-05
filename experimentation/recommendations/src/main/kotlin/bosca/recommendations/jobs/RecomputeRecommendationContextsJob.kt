package bosca.recommendations.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Reclassifies persisted content after a recommendation context definition changes. */
@Serializable
class RecomputeRecommendationContextsJob(val trainModels: Boolean = true) : IJobDefinition

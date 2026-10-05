package bosca.segmentation.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Job payload for asynchronously evaluating a dynamic segment's analytics query
 * and refreshing its membership table with the resulting profile IDs.
 */
@Serializable
data class EvaluateSegmentJob(
    @Contextual
    val segmentId: UUID
) : IJobDefinition

package bosca.experimentation.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Job payload for aggregating experiment metrics from the analytics data warehouse.
 * Computes per-variant impression counts, conversion counts, conversion rates,
 * statistical significance, and lift over control for each conversion goal.
 */
@Serializable
data class ExperimentResultAggregationJob(
    @Contextual
    val experimentId: UUID
) : IJobDefinition

package bosca.recommendations.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Job payload for triggering evaluation of a recommendation strategy.
 * When executed, the strategy's analytics query is run against the
 * data warehouse and the resulting recommendations are upserted into
 * the recommendations table.
 */
@Serializable
data class EvaluateStrategyJob(
    @Contextual
    val strategyId: UUID,
) : IJobDefinition

package bosca.recommendations.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Input for creating or updating a recommendation strategy, specifying
 * the algorithm type, analytics query binding, targeting configuration,
 * and evaluation schedule.
 */
@Serializable
data class RecommendationStrategyInput(
    val name: String,
    val description: String = "",
    val type: RecommendationStrategyType,
    val status: RecommendationStrategyStatus = RecommendationStrategyStatus.DRAFT,
    @Contextual
    val analyticsQueryId: UUID? = null,
    @Contextual
    val configuration: JsonElement? = null,
    val priority: Int = 0,
    val maxRecommendations: Int = 10,
    val evaluationSchedule: String? = null,
)

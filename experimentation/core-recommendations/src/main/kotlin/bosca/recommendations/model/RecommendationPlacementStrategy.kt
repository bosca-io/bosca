package bosca.recommendations.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Associates a recommendation placement with a strategy, controlling
 * which strategies contribute recommendations to each display location.
 * The priority field determines the order in which strategies are
 * evaluated and blended when filling a placement's recommendation slots.
 */
@Serializable
data class RecommendationPlacementStrategy(
    @ColumnName("placement_id")
    @Contextual
    val placementId: UUID,
    @ColumnName("strategy_id")
    @Contextual
    val strategyId: UUID,
    val priority: Int = 0,
)

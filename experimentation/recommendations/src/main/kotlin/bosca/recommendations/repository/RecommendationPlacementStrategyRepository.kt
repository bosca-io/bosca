package bosca.recommendations.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.recommendations.model.RecommendationPlacementStrategy
import bosca.serialization.UUID

/**
 * Manages the many-to-many association between placements and strategies with a
 * priority ordering. When a placement is resolved at query time, the linked strategies
 * are evaluated in descending priority order to source and rank recommendation candidates.
 */
@Repository
interface RecommendationPlacementStrategyRepository {

    @Query("select * from recommendations.placement_strategies where placement_id = :placementId order by priority desc")
    suspend fun getByPlacementId(placementId: UUID): List<RecommendationPlacementStrategy>

    @Query("select strategy_id from recommendations.placement_strategies where placement_id = :placementId order by priority desc")
    suspend fun getStrategyIdsByPlacementId(placementId: UUID): List<UUID>

    @Query("insert into recommendations.placement_strategies (placement_id, strategy_id, priority) values (:placementId, :strategyId, :priority) on conflict do nothing")
    suspend fun add(placementId: UUID, strategyId: UUID, priority: Int)

    @Query("delete from recommendations.placement_strategies where placement_id = :placementId")
    suspend fun deleteByPlacementId(placementId: UUID)
}

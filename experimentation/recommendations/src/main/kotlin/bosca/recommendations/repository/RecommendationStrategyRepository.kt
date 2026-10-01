package bosca.recommendations.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.model.RecommendationStrategyStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Persists recommendation strategies that define how candidates are sourced and scored.
 * Each strategy has a type (e.g., trending, curated, personalized), an optional analytics
 * query for data-driven evaluation, a priority for ordering when multiple strategies feed
 * the same placement, and lifecycle status tracking with optional cron-based scheduling.
 */
@Repository
interface RecommendationStrategyRepository {

    @Query("select * from recommendations.strategies order by priority desc, created desc limit :limit offset :offset")
    suspend fun getAll(offset: Long, limit: Int): List<RecommendationStrategy>

    @Query("select * from recommendations.strategies where id = :id")
    suspend fun getById(id: UUID): RecommendationStrategy?

    @Query("select * from recommendations.strategies where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<RecommendationStrategy>

    @Query("select * from recommendations.strategies where status = 'active' order by priority desc")
    suspend fun getActive(): List<RecommendationStrategy>

    @Query("""
        insert into recommendations.strategies (name, description, type, status, analytics_query_id, configuration, priority, max_recommendations, scheduled_job_id)
        values (:name, :description, (:type)::recommendations.strategy_type, (:status)::recommendations.strategy_status, :analyticsQueryId, :configuration::jsonb, :priority, :maxRecommendations, :scheduledJobId)
        returning *
    """)
    suspend fun add(strategy: RecommendationStrategy): RecommendationStrategy

    @Query("""
        update recommendations.strategies
        set name = :name, description = :description, type = (:type)::recommendations.strategy_type,
            status = (:status)::recommendations.strategy_status, analytics_query_id = :analyticsQueryId,
            configuration = :configuration::jsonb, priority = :priority, max_recommendations = :maxRecommendations,
            scheduled_job_id = :scheduledJobId, modified = now()
        where id = :id
        returning *
    """)
    suspend fun update(strategy: RecommendationStrategy): RecommendationStrategy

    @Query("update recommendations.strategies set status = (:status)::recommendations.strategy_status, modified = now() where id = :id returning *")
    suspend fun updateStatus(id: UUID, status: RecommendationStrategyStatus): RecommendationStrategy

    @Query("update recommendations.strategies set last_evaluated = :lastEvaluated, modified = now() where id = :id returning *")
    suspend fun updateLastEvaluated(id: UUID, lastEvaluated: OffsetDateTime): RecommendationStrategy

    @Query("update recommendations.strategies set scheduled_job_id = :scheduledJobId, modified = now() where id = :id returning *")
    suspend fun updateScheduledJobId(id: UUID, scheduledJobId: UUID?): RecommendationStrategy

    @Query("delete from recommendations.strategies where id = :id")
    suspend fun deleteById(id: UUID)
}

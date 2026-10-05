package bosca.experimentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.experimentation.model.ConversionGoal
import bosca.serialization.UUID

@Repository
interface ConversionGoalRepository {

    @Query("select * from experimentation.conversion_goals where experiment_id = :experimentId order by created")
    suspend fun getByExperimentId(experimentId: UUID): List<ConversionGoal>

    @Query("""
        select * from experimentation.conversion_goals
        where experiment_id = :experimentId
        order by created
        limit :limit offset :offset
    """)
    suspend fun getByExperimentId(experimentId: UUID, offset: Long, limit: Int): List<ConversionGoal>

    @Query("select * from experimentation.conversion_goals where id = :id")
    suspend fun getById(id: UUID): ConversionGoal?

    @Query("""
        insert into experimentation.conversion_goals (experiment_id, name, event_type, element_type, element_id, metric_type, page_path, page_path_prefixes, item_extra_key, item_extra_value, role, cuped_covariate)
        values (:experimentId, :name, :eventType, :elementType, :elementId, (:metricType)::experimentation.goal_metric_type, :pagePath, :pagePathPrefixes, :itemExtraKey, :itemExtraValue, (:role)::experimentation.conversion_goal_role, :cupedCovariate::jsonb)
        returning *
    """)
    suspend fun add(goal: ConversionGoal): ConversionGoal

    @Query("""
        update experimentation.conversion_goals
        set name = :name,
            event_type = :eventType,
            element_type = :elementType,
            element_id = :elementId,
            metric_type = (:metricType)::experimentation.goal_metric_type,
            page_path = :pagePath,
            page_path_prefixes = :pagePathPrefixes,
            item_extra_key = :itemExtraKey,
            item_extra_value = :itemExtraValue,
            role = (:role)::experimentation.conversion_goal_role,
            cuped_covariate = :cupedCovariate::jsonb
        where id = :id
        returning *
    """)
    suspend fun update(goal: ConversionGoal): ConversionGoal?

    @Query("delete from experimentation.conversion_goals where id = :id")
    suspend fun deleteById(id: UUID)
}

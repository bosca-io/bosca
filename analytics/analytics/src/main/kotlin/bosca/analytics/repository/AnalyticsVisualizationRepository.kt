package bosca.analytics.repository

import bosca.analytics.model.AnalyticsVisualization
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface AnalyticsVisualizationRepository {

    @Query("select * from analytics_visualizations order by name offset :offset limit :limit")
    suspend fun getAll(offset: Long, limit: Int): List<AnalyticsVisualization>

    @Query("select * from analytics_visualizations where id = :id")
    suspend fun getById(id: UUID): AnalyticsVisualization?

    @Query("select * from analytics_visualizations where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<AnalyticsVisualization>

    @Query("select * from analytics_visualizations where key = :key")
    suspend fun getByKey(key: String): AnalyticsVisualization?

    @Query("insert into analytics_visualizations (key, name, description, query_id, type, configuration) values (:key, :name, :description, :queryId, (:type)::analytics_visualization_type, :configuration) returning *")
    suspend fun add(visualization: AnalyticsVisualization): AnalyticsVisualization

    @Query("update analytics_visualizations set key = :key, name = :name, description = :description, query_id = :queryId, type = (:type)::analytics_visualization_type, configuration = :configuration where id = :id")
    suspend fun edit(visualization: AnalyticsVisualization)

    @Query("delete from analytics_visualizations where id = :id")
    suspend fun deleteById(id: UUID)
}

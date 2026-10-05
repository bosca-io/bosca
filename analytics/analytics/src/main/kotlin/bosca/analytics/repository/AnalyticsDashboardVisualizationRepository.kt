package bosca.analytics.repository

import bosca.analytics.model.AnalyticsDashboardVisualization
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface AnalyticsDashboardVisualizationRepository {

    @Query("insert into analytics_dashboard_visualizations (id, dashboard_id, visualization_id, configuration) values (:id, :dashboardId, :visualizationId, :configuration)")
    suspend fun add(id: UUID, dashboardId: UUID, visualizationId: UUID, configuration: JsonElement)

    @Query("delete from analytics_dashboard_visualizations where id = :id")
    suspend fun remove(id: UUID)

    @Query("select * from analytics_dashboard_visualizations where dashboard_id = :dashboardId")
    suspend fun getVisualizationsByDashboardId(dashboardId: UUID): List<AnalyticsDashboardVisualization>

    @Query("delete from analytics_dashboard_visualizations where dashboard_id = :dashboardId")
    suspend fun removeAll(dashboardId: UUID)
}

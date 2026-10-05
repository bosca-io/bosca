package bosca.analytics.repository

import bosca.analytics.model.AnalyticsDashboard
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface AnalyticsDashboardRepository {

    @Query("select * from analytics_dashboards order by name offset :offset limit :limit")
    suspend fun getAll(offset: Long, limit: Int): List<AnalyticsDashboard>

    @Query("select * from analytics_dashboards where id = :id")
    suspend fun getById(id: UUID): AnalyticsDashboard?

    @Query("select * from analytics_dashboards where key = :key")
    suspend fun getByKey(key: String): AnalyticsDashboard?

    @Query("insert into analytics_dashboards (key, name, description, configuration, parameters) values (:key, :name, :description, :configuration, :parameters) returning *")
    suspend fun add(dashboard: AnalyticsDashboard): AnalyticsDashboard

    @Query("update analytics_dashboards set key = :key, name = :name, description = :description, configuration = :configuration, parameters = :parameters where id = :id")
    suspend fun edit(dashboard: AnalyticsDashboard)

    @Query("delete from analytics_dashboards where id = :id")
    suspend fun deleteById(id: UUID)
}

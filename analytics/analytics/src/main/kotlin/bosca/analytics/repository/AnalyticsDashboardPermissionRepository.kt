package bosca.analytics.repository

import bosca.analytics.model.AnalyticsDashboardPermission
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

@Repository
interface AnalyticsDashboardPermissionRepository {

    @Query("insert into analytics_dashboard_permissions (group_id, dashboard_id, action) values (:groupId, :dashboardId, (:action)::permission_action) on conflict do nothing")
    suspend fun addPermission(dashboardId: UUID, groupId: UUID, action: PermissionAction)

    @Query("delete from analytics_dashboard_permissions where group_id = :groupId and dashboard_id = :dashboardId and action = (:action)::permission_action")
    suspend fun deletePermission(dashboardId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from analytics_dashboard_permissions where dashboard_id = :id")
    suspend fun getPermissionsById(id: UUID): List<AnalyticsDashboardPermission>

    @Query("select * from analytics_dashboard_permissions where dashboard_id = any(:id)")
    suspend fun getPermissionsByIds(id: List<UUID>): List<AnalyticsDashboardPermission>
}

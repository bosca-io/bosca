package bosca.analytics.repository

import bosca.analytics.model.AnalyticsVisualizationPermission
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

@Repository
interface AnalyticsVisualizationPermissionRepository {

    @Query("insert into analytics_visualization_permissions (group_id, visualization_id, action) values (:groupId, :visualizationId, (:action)::permission_action) on conflict do nothing")
    suspend fun addPermission(visualizationId: UUID, groupId: UUID, action: PermissionAction)

    @Query("delete from analytics_visualization_permissions where group_id = :groupId and visualization_id = :visualizationId and action = (:action)::permission_action")
    suspend fun deletePermission(visualizationId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from analytics_visualization_permissions where visualization_id = :id")
    suspend fun getPermissionsById(id: UUID): List<AnalyticsVisualizationPermission>

    @Query("select * from analytics_visualization_permissions where visualization_id = any(:id)")
    suspend fun getPermissionsByIds(id: List<UUID>): List<AnalyticsVisualizationPermission>
}

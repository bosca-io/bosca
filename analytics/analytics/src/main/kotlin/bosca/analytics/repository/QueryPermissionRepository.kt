package bosca.analytics.repository

import bosca.analytics.model.QueryDefinitionPermission
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

@Repository
interface QueryPermissionRepository {

    @Query("insert into analytics_query_permissions (group_id, query_id, action) values (:groupId, :queryId, (:action)::permission_action) on conflict do nothing")
    suspend fun addPermission(queryId: UUID, groupId: UUID, action: PermissionAction)

    @Query("delete from analytics_query_permissions where group_id = :groupId and query_id = :queryId and action = (:action)::permission_action")
    suspend fun deletePermission(queryId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from analytics_query_permissions where query_id = :id")
    suspend fun getPermissionsById(id: UUID): List<QueryDefinitionPermission>

    @Query("select * from analytics_query_permissions where query_id = any(:id)")
    suspend fun getPermissionsByIds(id: List<UUID>): List<QueryDefinitionPermission>
}
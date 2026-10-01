package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.workops.model.permission.EnvironmentPermission

@Repository
interface EnvironmentPermissionRepository {

    @Query("insert into workops.environment_permissions (environment_id, group_id, action) values (:environmentId, :groupId, (:action)::permission_action) on conflict do nothing")
    suspend fun add(environmentId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from workops.environment_permissions where environment_id = :id")
    suspend fun getByEnvironmentId(id: UUID): List<EnvironmentPermission>

    @Query("select * from workops.environment_permissions where environment_id = any(:ids)")
    suspend fun getByEnvironmentIds(ids: List<UUID>): List<EnvironmentPermission>

    @Query("delete from workops.environment_permissions where environment_id = :id and group_id = :groupId and action = (:action)::permission_action")
    suspend fun delete(id: UUID, groupId: UUID, action: PermissionAction)
}

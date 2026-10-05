package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.workops.model.permission.ProjectPermission

@Repository
interface ProjectPermissionRepository {

    @Query("insert into workops.project_permissions (project_id, group_id, action) values (:projectId, :groupId, (:action)::permission_action) on conflict do nothing")
    suspend fun add(projectId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from workops.project_permissions where project_id = :id")
    suspend fun getByProjectId(id: UUID): List<ProjectPermission>

    @Query("select * from workops.project_permissions where project_id = any(:ids)")
    suspend fun getByProjectIds(ids: List<UUID>): List<ProjectPermission>

    @Query("delete from workops.project_permissions where project_id = :id and group_id = :groupId and action = (:action)::permission_action")
    suspend fun delete(id: UUID, groupId: UUID, action: PermissionAction)
}

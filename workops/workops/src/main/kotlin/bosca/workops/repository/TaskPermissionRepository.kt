package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.workops.model.permission.TaskPermission

@Repository
interface TaskPermissionRepository {

    @Query("insert into workops.task_permissions (task_id, group_id, action) values (:taskId, :groupId, (:action)::permission_action) on conflict do nothing")
    suspend fun add(taskId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from workops.task_permissions where task_id = :id")
    suspend fun getByTaskId(id: UUID): List<TaskPermission>

    @Query("select * from workops.task_permissions where task_id = any(:ids)")
    suspend fun getByTaskIds(ids: List<UUID>): List<TaskPermission>

    @Query("delete from workops.task_permissions where task_id = :id and group_id = :groupId and action = (:action)::permission_action")
    suspend fun delete(id: UUID, groupId: UUID, action: PermissionAction)
}

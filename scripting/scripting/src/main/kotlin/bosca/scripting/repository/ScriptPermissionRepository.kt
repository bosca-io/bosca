package bosca.scripting.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.scripting.model.ScriptPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

/**
 * Data access layer for script permission grants stored in the `scripting.script_permissions` table.
 *
 * Each row links a script to a security group with a specific [PermissionAction], controlling
 * which groups can view, execute, or manage individual scripts.
 */
@Repository
interface ScriptPermissionRepository {

    @Query("insert into scripting.script_permissions (group_id, script_id, action) values (:groupId, :scriptId, (:action)::permission_action) on conflict do nothing")
    suspend fun addPermission(scriptId: UUID, groupId: UUID, action: PermissionAction)

    @Query("delete from scripting.script_permissions where group_id = :groupId and script_id = :scriptId and action = (:action)::permission_action")
    suspend fun deletePermission(scriptId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from scripting.script_permissions where script_id = :id")
    suspend fun getPermissionsByScriptId(id: UUID): List<ScriptPermission>

    @Query("select * from scripting.script_permissions where script_id = any(:id)")
    suspend fun getPermissionsByScriptIds(id: List<UUID>): List<ScriptPermission>
}

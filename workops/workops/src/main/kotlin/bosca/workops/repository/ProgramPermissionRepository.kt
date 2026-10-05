package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.workops.model.permission.ProgramPermission

@Repository
interface ProgramPermissionRepository {

    @Query("insert into workops.program_permissions (program_id, group_id, action) values (:programId, :groupId, (:action)::permission_action) on conflict do nothing")
    suspend fun add(programId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from workops.program_permissions where program_id = :id")
    suspend fun getByProgramId(id: UUID): List<ProgramPermission>

    @Query("select * from workops.program_permissions where program_id = any(:ids)")
    suspend fun getByProgramIds(ids: List<UUID>): List<ProgramPermission>

    @Query("delete from workops.program_permissions where program_id = :id and group_id = :groupId and action = (:action)::permission_action")
    suspend fun delete(id: UUID, groupId: UUID, action: PermissionAction)
}

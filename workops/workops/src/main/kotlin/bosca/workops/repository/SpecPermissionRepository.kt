package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.workops.model.permission.RequirementPermission
import bosca.workops.model.permission.SpecPermission

@Repository
interface SpecPermissionRepository {

    @Query("insert into workops.spec_permissions (spec_id, group_id, action) values (:specId, :groupId, (:action)::permission_action) on conflict do nothing")
    suspend fun add(specId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from workops.spec_permissions where spec_id = :id")
    suspend fun getBySpecId(id: UUID): List<SpecPermission>

    @Query("select * from workops.spec_permissions where spec_id = any(:ids)")
    suspend fun getBySpecIds(ids: List<UUID>): List<SpecPermission>

    @Query("delete from workops.spec_permissions where spec_id = :id and group_id = :groupId and action = (:action)::permission_action")
    suspend fun delete(id: UUID, groupId: UUID, action: PermissionAction)
}

@Repository
interface RequirementPermissionRepository {

    @Query("insert into workops.requirement_permissions (requirement_id, group_id, action) values (:requirementId, :groupId, (:action)::permission_action) on conflict do nothing")
    suspend fun add(requirementId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from workops.requirement_permissions where requirement_id = :id")
    suspend fun getByRequirementId(id: UUID): List<RequirementPermission>

    @Query("select * from workops.requirement_permissions where requirement_id = any(:ids)")
    suspend fun getByRequirementIds(ids: List<UUID>): List<RequirementPermission>

    @Query("delete from workops.requirement_permissions where requirement_id = :id and group_id = :groupId and action = (:action)::permission_action")
    suspend fun delete(id: UUID, groupId: UUID, action: PermissionAction)
}

package bosca.profile.organization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.organization.model.OrganizationPermission
import bosca.serialization.UUID

@Repository
interface OrganizationPermissionRepository {

    @Query("select * from organization_permissions where organization_id = :id")
    suspend fun getById(id: UUID): List<OrganizationPermission>

    @Query("select * from organization_permissions where organization_id = any(:ids)")
    suspend fun getBatch(ids: List<UUID>): List<OrganizationPermission>

    @Query("insert into organization_permissions (organization_id, group_id, action) values (:organizationId, :groupId, :action)")
    suspend fun add(permission: OrganizationPermission)

    @Query("delete from organization_permissions where organization_id = :organizationId and group_id = :groupId and action = :action")
    suspend fun delete(permission: OrganizationPermission)
}
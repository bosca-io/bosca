package bosca.community.repository

import bosca.community.model.CommunityGroupPermission
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

@Repository
interface CommunityGroupPermissionRepository {

    @Query("insert into community_group_permissions (community_group_id, group_id, action) values (:communityGroupId, :groupId, (:action)::permission_action) on conflict do nothing")
    suspend fun addPermission(communityGroupId: UUID, groupId: UUID, action: PermissionAction)

    @Query("delete from community_group_permissions where group_id = :groupId and community_group_id = :communityGroupId and action = (:action)::permission_action")
    suspend fun deletePermission(communityGroupId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from community_group_permissions where community_group_id = :id")
    suspend fun getPermissionsByCommunityGroupId(id: UUID): List<CommunityGroupPermission>

    @Query("select * from community_group_permissions where community_group_id = any(:ids)")
    suspend fun getPermissionsByCommunityGroupIds(ids: List<UUID>): List<CommunityGroupPermission>
}

package bosca.community.repository

import bosca.community.model.CommunityGroup
import bosca.community.model.CommunityGroupMember
import bosca.community.model.CommunityGroupType
import bosca.community.model.CommunityVisibility
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface CommunityGroupRepository {

    @Query("select cg.* from community_groups cg join community_group_members cgm on cg.id = cgm.group_id where cgm.profile_id = :profileId")
    suspend fun getGroupsByProfileId(profileId: UUID): List<CommunityGroup>

    @Query("select * from community_groups limit :limit offset :offset")
    suspend fun getGroups(limit: Int, offset: Long): List<CommunityGroup>

    @Query("select count(*) from community_groups")
    suspend fun getGroupCount(): Long

    @Query("select * from community_groups where id = :id")
    suspend fun getGroup(id: UUID): CommunityGroup?

    @Query("insert into community_groups (name, description, type, visibility, attributes) values (:name, :description, :type, :visibility, :attributes) returning *")
    suspend fun createGroup(name: String, description: String, type: CommunityGroupType, visibility: CommunityVisibility, attributes: JsonElement?): CommunityGroup

    @Query("update community_groups set name = coalesce(:name, name), description = coalesce(:description, description), type = coalesce(:type::community_group_type, type), visibility = coalesce(:visibility::community_visibility, visibility), attributes = coalesce(:attributes, attributes) where id = :id returning *")
    suspend fun updateGroup(id: UUID, name: String?, description: String?, type: CommunityGroupType?, visibility: CommunityVisibility?, attributes: JsonElement?): CommunityGroup

    @Query("insert into community_group_members (group_id, profile_id) values (:groupId, :profileId) on conflict (group_id, profile_id) do nothing")
    suspend fun addMember(groupId: UUID, profileId: UUID)

    @Query("delete from community_group_members where group_id = :groupId and profile_id = :profileId")
    suspend fun removeMember(groupId: UUID, profileId: UUID)

    @Query("select * from community_group_members where group_id = :groupId")
    suspend fun getMembers(groupId: UUID): List<CommunityGroupMember>
}

package bosca.community.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface PrayerCommunityGroupRepository {

    @Query("insert into community.prayer_community_groups (prayer_id, community_group_id) values (:prayerId, :communityGroupId) on conflict do nothing")
    suspend fun addCommunityGroup(prayerId: UUID, communityGroupId: UUID)

    @Query("delete from community.prayer_community_groups where prayer_id = :prayerId and community_group_id = :communityGroupId")
    suspend fun removeCommunityGroup(prayerId: UUID, communityGroupId: UUID)

    @Query("select community_group_id from community.prayer_community_groups where prayer_id = :prayerId")
    suspend fun getCommunityGroupIds(prayerId: UUID): List<UUID>

    @Query("select community_group_id from community.prayer_community_groups where prayer_id = :prayerId limit 1")
    suspend fun getFirstCommunityGroupId(prayerId: UUID): UUID?
}

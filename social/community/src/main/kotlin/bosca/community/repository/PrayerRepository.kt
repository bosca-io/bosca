package bosca.community.repository

import bosca.community.model.PrayedByEntry
import bosca.community.model.Prayer
import bosca.community.model.PrayerFeedFilter
import bosca.community.model.PrayerLike
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface PrayerRepository {

    @Query(
        """
        select p.* from community.prayers p
        join community.prayer_community_groups pcg on p.id = pcg.prayer_id
        where pcg.community_group_id = :communityGroupId
        order by p.last_activity_at desc
        limit :limit offset :offset
        """
    )
    suspend fun getRequests(communityGroupId: UUID, limit: Int, offset: Int): List<Prayer>

    @Query("select * from community.prayers where id = :id")
    suspend fun getRequest(id: UUID): Prayer?

    @Query("insert into community.prayers (profile_id, title, content, attributes) values (:profileId, :title, :content, :attributes) returning *")
    suspend fun addRequest(profileId: UUID, title: String, content: JsonElement, attributes: JsonElement?): Prayer

    @Query("delete from community.prayers where id = :id")
    suspend fun deleteRequest(id: UUID)

    @Query(
        """
        select p.* from community.prayers p
        join community.prayer_community_groups pcg on p.id = pcg.prayer_id
        where pcg.community_group_id = :communityGroupId
        and p.status::text = any(string_to_array(:statuses, ','))
        order by p.last_activity_at desc, p.id desc
        limit :limit offset :offset
        """
    )
    suspend fun getRequestsByStatus(communityGroupId: UUID, statuses: String, limit: Int, offset: Int): List<Prayer>

    @Query(
        """
        select count(*) from community.prayers p
        join community.prayer_community_groups pcg on p.id = pcg.prayer_id
        where pcg.community_group_id = :communityGroupId
        """
    )
    suspend fun countRequests(communityGroupId: UUID): Long

    @Query(
        """
        select count(*) from community.prayers p
        join community.prayer_community_groups pcg on p.id = pcg.prayer_id
        where pcg.community_group_id = :communityGroupId
        and p.status::text = any(string_to_array(:statuses, ','))
        """
    )
    suspend fun countRequestsByStatus(communityGroupId: UUID, statuses: String): Long

    @Query(
        """
        select distinct p.* from community.prayers p
        join community.prayer_community_groups pcg on p.id = pcg.prayer_id
        where pcg.community_group_id = any(:communityGroupIds)
        order by p.last_activity_at desc, p.id desc
        limit :limit offset :offset
        """
    )
    suspend fun getRequestsByGroups(filter: PrayerFeedFilter): List<Prayer>

    @Query(
        """
        select distinct p.* from community.prayers p
        join community.prayer_community_groups pcg on p.id = pcg.prayer_id
        where pcg.community_group_id = any(:communityGroupIds)
        and p.status::text = any(string_to_array(:statuses, ','))
        order by p.last_activity_at desc, p.id desc
        limit :limit offset :offset
        """
    )
    suspend fun getRequestsByGroupsAndStatus(filter: PrayerFeedFilter): List<Prayer>

    @Query(
        """
        select count(distinct p.id) from community.prayers p
        join community.prayer_community_groups pcg on p.id = pcg.prayer_id
        where pcg.community_group_id = any(:communityGroupIds)
        """
    )
    suspend fun countRequestsByGroups(filter: PrayerFeedFilter): Long

    @Query(
        """
        select count(distinct p.id) from community.prayers p
        join community.prayer_community_groups pcg on p.id = pcg.prayer_id
        where pcg.community_group_id = any(:communityGroupIds)
        and p.status::text = any(string_to_array(:statuses, ','))
        """
    )
    suspend fun countRequestsByGroupsAndStatus(filter: PrayerFeedFilter): Long

    @Query(
        """
        update community.prayers set
            status = :status::community.prayer_status,
            modified = now(),
            last_activity_at = now(),
            answered_at = case when :status = 'answered' then now() else answered_at end
        where id = :id
        returning *
        """
    )
    suspend fun updateStatus(id: UUID, status: String): Prayer?

    @Query(
        """
        update community.prayers set
            prayer_action_count = prayer_action_count + 1,
            last_activity_at = now(),
            modified = now()
        where id = :prayerId
        returning prayer_action_count
        """
    )
    suspend fun incrementPrayerActionCount(prayerId: UUID): Int?

    @Query(
        """
        update community.prayers set
            prayer_action_count = greatest(prayer_action_count - 1, 0),
            modified = now()
        where id = :prayerId
        returning prayer_action_count
        """
    )
    suspend fun decrementPrayerActionCount(prayerId: UUID): Int?

    @Query("insert into community.prayer_prayed_by (prayer_id, profile_id) values (:prayerId, :profileId)")
    suspend fun addPrayedBy(prayerId: UUID, profileId: UUID)

    @Query("delete from community.prayer_prayed_by where prayer_id = :prayerId and profile_id = :profileId returning prayer_id")
    suspend fun deletePrayedBy(prayerId: UUID, profileId: UUID): UUID?

    @Query("select * from community.prayer_prayed_by where prayer_id = :prayerId order by prayed_at desc limit :limit offset :offset")
    suspend fun getPrayedBy(prayerId: UUID, limit: Int, offset: Int): List<PrayedByEntry>

    @Query("select exists(select 1 from community.prayer_prayed_by where prayer_id = :prayerId and profile_id = :profileId)")
    suspend fun hasPrayed(prayerId: UUID, profileId: UUID): Boolean

    @Query(
        """
        update community.prayers set
            like_count = like_count + 1,
            last_activity_at = now(),
            modified = now()
        where id = :prayerId
        returning like_count
        """
    )
    suspend fun incrementLikeCount(prayerId: UUID): Int?

    @Query(
        """
        update community.prayers set
            like_count = greatest(like_count - 1, 0),
            modified = now()
        where id = :prayerId
        returning like_count
        """
    )
    suspend fun decrementLikeCount(prayerId: UUID): Int?

    @Query("insert into community.prayer_likes (prayer_id, profile_id) values (:prayerId, :profileId)")
    suspend fun addLike(prayerId: UUID, profileId: UUID)

    @Query("delete from community.prayer_likes where prayer_id = :prayerId and profile_id = :profileId returning prayer_id")
    suspend fun deleteLike(prayerId: UUID, profileId: UUID): UUID?

    @Query("select * from community.prayer_likes where prayer_id = :prayerId order by liked_at desc limit :limit offset :offset")
    suspend fun getLikes(prayerId: UUID, limit: Int, offset: Int): List<PrayerLike>

    @Query("select exists(select 1 from community.prayer_likes where prayer_id = :prayerId and profile_id = :profileId)")
    suspend fun hasLiked(prayerId: UUID, profileId: UUID): Boolean

    @Query("select * from community.prayers where status = 'answered' and answered_at is not null and not suppress_anniversaries")
    suspend fun getAnsweredPrayersForAnniversaryScan(): List<Prayer>

    @Query("update community.prayers set suppress_anniversaries = :suppress, modified = now() where id = :id returning *")
    suspend fun updateSuppressAnniversaries(id: UUID, suppress: Boolean): Prayer?

    @Query(
        """
        update community.prayers set
            comment_count = comment_count + 1,
            last_activity_at = now(),
            modified = now()
        where id = :prayerId
        returning comment_count
        """
    )
    suspend fun incrementCommentCount(prayerId: UUID): Int?

    @Query(
        """
        update community.prayers set
            comment_count = greatest(comment_count - 1, 0),
            modified = now()
        where id = :prayerId
        returning comment_count
        """
    )
    suspend fun decrementCommentCount(prayerId: UUID): Int?
}

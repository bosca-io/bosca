package bosca.segmentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.segmentation.model.SegmentMember
import bosca.serialization.UUID

@Repository
interface SegmentMemberRepository {

    @Query("select * from segmentation.segment_members where segment_id = :segmentId order by added_at desc limit :limit offset :offset")
    suspend fun getMembers(segmentId: UUID, offset: Long, limit: Int): List<SegmentMember>

    @Query("select count(*) from segmentation.segment_members where segment_id = :segmentId")
    suspend fun getMemberCount(segmentId: UUID): Long

    @Query("insert into segmentation.segment_members (segment_id, profile_id) values (:segmentId, :profileId) on conflict do nothing")
    suspend fun addMember(segmentId: UUID, profileId: UUID)

    @Query("delete from segmentation.segment_members where segment_id = :segmentId and profile_id = :profileId")
    suspend fun removeMember(segmentId: UUID, profileId: UUID)

    @Query("delete from segmentation.segment_members where segment_id = :segmentId")
    suspend fun removeAllMembers(segmentId: UUID)

    @Query("select distinct profile_id from segmentation.segment_members where segment_id = any(:segmentIds)")
    suspend fun getProfileIdsBySegments(segmentIds: List<UUID>): List<UUID>

    @Query("select count(distinct profile_id) from segmentation.segment_members where segment_id = any(:segmentIds)")
    suspend fun countProfileIdsBySegments(segmentIds: List<UUID>): Long

    @Query("select exists(select 1 from segmentation.segment_members where segment_id = :segmentId and profile_id = :profileId)")
    suspend fun isMember(segmentId: UUID, profileId: UUID): Boolean

    @Query("select segment_id from segmentation.segment_members where profile_id = :profileId order by added_at desc")
    suspend fun getSegmentIdsByProfileId(profileId: UUID): List<UUID>
}

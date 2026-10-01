package bosca.community.repository

import bosca.community.model.Prayer
import bosca.community.model.PrayerShare
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface PrayerShareRepository {

    @Query("insert into community.prayer_shares (prayer_id, profile_id) values (:prayerId, :profileId) on conflict do nothing")
    suspend fun addShare(prayerId: UUID, profileId: UUID)

    @Query("delete from community.prayer_shares where prayer_id = :prayerId and profile_id = :profileId")
    suspend fun deleteShare(prayerId: UUID, profileId: UUID)

    @Query("select * from community.prayer_shares where prayer_id = :prayerId order by shared_at desc")
    suspend fun getShares(prayerId: UUID): List<PrayerShare>

    @Query(
        """
        select p.* from community.prayers p
        join community.prayer_shares ps on p.id = ps.prayer_id
        where ps.profile_id = :profileId
        order by p.last_activity_at desc
        limit :limit offset :offset
        """
    )
    suspend fun getSharedWithProfile(profileId: UUID, limit: Int, offset: Int): List<Prayer>

    @Query("select count(*) from community.prayer_shares where profile_id = :profileId")
    suspend fun countSharedWithProfile(profileId: UUID): Long
}

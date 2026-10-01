package bosca.community.repository

import bosca.community.model.PrayerAnniversary
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface PrayerAnniversaryRepository {

    @Query("select * from community.prayer_anniversaries where prayer_id = :prayerId order by posted_at desc")
    suspend fun getAnniversaries(prayerId: UUID): List<PrayerAnniversary>

    @Query("insert into community.prayer_anniversaries (prayer_id, milestone) values (:prayerId, :milestone) on conflict do nothing")
    suspend fun addAnniversary(prayerId: UUID, milestone: String)
}

package bosca.communications.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreference
import bosca.serialization.UUID

@Repository
interface NotificationPreferenceRepository {

    @Query("select * from communications.notification_preferences where profile_id = :profileId")
    suspend fun getByProfileId(profileId: UUID): List<NotificationPreference>

    @Query("select * from communications.notification_preferences where profile_id = :profileId and channel = :channel::communications.channel and type = :type")
    suspend fun get(profileId: UUID, channel: DeliveryChannel, type: String): NotificationPreference?

    @Query("""
        insert into communications.notification_preferences (profile_id, channel, type, opted_out)
        values (:profileId, :channel::communications.channel, :type, :optedOut)
        on conflict (profile_id, channel, type)
        do update set opted_out = :optedOut, updated_at = now()
        returning *
    """)
    suspend fun upsert(
        profileId: UUID,
        channel: DeliveryChannel,
        type: String,
        optedOut: Boolean,
    ): NotificationPreference

    @Query("""
        delete from communications.notification_preferences
        where channel = :channel::communications.channel and type = :type
    """, returnUpdateCount = true)
    suspend fun deleteByChannelAndType(channel: DeliveryChannel, type: String): Int
}

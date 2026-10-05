package bosca.communications.repository

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreferenceMapping
import bosca.db.annotation.Query
import bosca.db.annotation.Repository

@Repository
interface NotificationPreferenceMappingRepository {

    @Query("select * from communications.notification_preference_mappings order by type, channel")
    suspend fun list(): List<NotificationPreferenceMapping>

    @Query("""
        select *
        from communications.notification_preference_mappings
        where type = :type and channel = :channel::communications.channel
    """)
    suspend fun get(type: String, channel: DeliveryChannel): NotificationPreferenceMapping?

    @Query("""
        insert into communications.notification_preference_mappings (type, channel, provider, external_id)
        values (:type, :channel::communications.channel, :provider, :externalId)
        on conflict (type, channel)
        do update set
            provider = :provider,
            external_id = :externalId,
            modified = now()
        returning *
    """)
    suspend fun upsert(
        type: String,
        channel: DeliveryChannel,
        provider: String,
        externalId: String,
    ): NotificationPreferenceMapping

    @Query("""
        delete from communications.notification_preference_mappings
        where type = :type and channel = :channel::communications.channel
    """, returnUpdateCount = true)
    suspend fun delete(type: String, channel: DeliveryChannel): Int
}

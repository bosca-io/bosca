package bosca.communications.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.communications.model.NotificationSettings
import bosca.serialization.UUID

@Repository
interface NotificationSettingsRepository {

    @Query("select * from communications.notification_settings where profile_id = :profileId")
    suspend fun get(profileId: UUID): NotificationSettings?

    @Query("""
        insert into communications.notification_settings (profile_id, time_zone, dnd_start_local, dnd_end_local)
        values (:profileId, :timeZone, :dndStartLocal, :dndEndLocal)
        on conflict (profile_id)
        do update set
            time_zone = :timeZone,
            dnd_start_local = :dndStartLocal,
            dnd_end_local = :dndEndLocal,
            updated_at = now()
        returning *
    """)
    suspend fun upsert(
        profileId: UUID,
        timeZone: String?,
        dndStartLocal: String?,
        dndEndLocal: String?,
    ): NotificationSettings
}

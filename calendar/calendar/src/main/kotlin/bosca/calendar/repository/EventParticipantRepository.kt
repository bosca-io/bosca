package bosca.calendar.repository

import bosca.calendar.model.EventParticipant
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface EventParticipantRepository {

    @Query("select * from calendar.event_participants where event_id = :eventId order by created asc")
    suspend fun getByEventId(eventId: UUID): List<EventParticipant>

    @Query("""
        insert into calendar.event_participants (event_id, profile_id, role, status)
        values (:eventId, :profileId, :role, :status)
        on conflict (event_id, profile_id) do update set
            role = excluded.role,
            status = excluded.status
        returning *
    """)
    suspend fun upsert(eventId: UUID, profileId: UUID, role: String, status: String): EventParticipant

    @Query("delete from calendar.event_participants where event_id = :eventId and profile_id = :profileId")
    suspend fun delete(eventId: UUID, profileId: UUID)
}

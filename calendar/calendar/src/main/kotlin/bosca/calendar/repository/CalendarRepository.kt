package bosca.calendar.repository

import bosca.calendar.model.Calendar
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface CalendarRepository {

    @Query("select * from calendar.calendars order by created desc")
    suspend fun getAll(): List<Calendar>

    @Query("select * from calendar.calendars where metadata_id = :metadataId and version = :version")
    suspend fun getById(metadataId: UUID, version: Int): Calendar?

    @Query("""
        insert into calendar.calendars (metadata_id, version, color, description)
        values (:metadataId, :version, :color, :description)
        on conflict (metadata_id, version) do nothing
        returning *
    """)
    suspend fun add(
        metadataId: UUID,
        version: Int,
        color: String,
        description: String
    ): Calendar?

    @Query("""
        update calendar.calendars
        set color = :color, description = :description, modified = now()
        where metadata_id = :metadataId and version = :version
        returning *
    """)
    suspend fun update(
        metadataId: UUID,
        version: Int,
        color: String,
        description: String
    ): Calendar
}

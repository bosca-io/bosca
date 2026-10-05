package bosca.calendar.repository

import bosca.calendar.model.CalendarEvent
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface CalendarEventRepository {

    /**
     * Returns rows that could contribute to the half-open range `[from, to)`.
     * Singles and overrides are filtered by their concrete window. Recurring
     * masters are returned whenever their template start is before [to];
     * expansion happens in the service layer because the SQL layer can't
     * reason about RRULE bounds.
     */
    @Query("""
        select * from calendar.events
        where metadata_id = :metadataId and version = :version
          and (
            (rrule is null and starts_at < :to and ends_at >= :from) or
            (rrule is not null and starts_at < :to)
          )
        order by starts_at asc
    """)
    suspend fun getCandidatesForRange(
        metadataId: UUID,
        version: Int,
        from: OffsetDateTime,
        to: OffsetDateTime
    ): List<CalendarEvent>

    /** Loads every override row for the given master events. */
    @Query("""
        select * from calendar.events
        where original_event_id = any(:masterIds)
        order by recurrence_id asc
    """)
    suspend fun getOverridesByMasterIds(masterIds: List<UUID>): List<CalendarEvent>

    /** Loads a single override (if any) for a specific master + recurrence id. */
    @Query("""
        select * from calendar.events
        where original_event_id = :masterId and recurrence_id = :recurrenceId
        limit 1
    """)
    suspend fun getOverride(masterId: UUID, recurrenceId: OffsetDateTime): CalendarEvent?

    @Query("select * from calendar.events where id = :id")
    suspend fun getById(id: UUID): CalendarEvent?

    @Query("""
        insert into calendar.events
            (metadata_id, version, title, description, location, all_day, starts_at, ends_at, rrule)
        values
            (:metadataId, :version, :title, :description, :location, :allDay, :startsAt, :endsAt, :rrule)
        returning *
    """)
    suspend fun add(
        metadataId: UUID,
        version: Int,
        title: String,
        description: String,
        location: String,
        allDay: Boolean,
        startsAt: OffsetDateTime,
        endsAt: OffsetDateTime,
        rrule: String?
    ): CalendarEvent

    @Query("""
        insert into calendar.events
            (metadata_id, version, title, description, location, all_day, starts_at, ends_at,
             original_event_id, recurrence_id)
        values
            (:metadataId, :version, :title, :description, :location, :allDay, :startsAt, :endsAt,
             :originalEventId, :recurrenceId)
        on conflict (original_event_id, recurrence_id) where original_event_id is not null
        do update set
            title = excluded.title,
            description = excluded.description,
            location = excluded.location,
            all_day = excluded.all_day,
            starts_at = excluded.starts_at,
            ends_at = excluded.ends_at,
            modified = now()
        returning *
    """)
    suspend fun upsertOverride(
        metadataId: UUID,
        version: Int,
        title: String,
        description: String,
        location: String,
        allDay: Boolean,
        startsAt: OffsetDateTime,
        endsAt: OffsetDateTime,
        originalEventId: UUID,
        recurrenceId: OffsetDateTime
    ): CalendarEvent

    @Query("""
        update calendar.events
        set title = :title, description = :description, location = :location,
            all_day = :allDay, starts_at = :startsAt, ends_at = :endsAt,
            rrule = :rrule, modified = now()
        where id = :id
        returning *
    """)
    suspend fun update(
        id: UUID,
        title: String,
        description: String,
        location: String,
        allDay: Boolean,
        startsAt: OffsetDateTime,
        endsAt: OffsetDateTime,
        rrule: String?
    ): CalendarEvent

    /** Sets the rrule on a master in place; used to apply UNTIL bounds when splitting a series. */
    @Query("update calendar.events set rrule = :rrule, modified = now() where id = :id returning *")
    suspend fun updateRrule(id: UUID, rrule: String?): CalendarEvent

    /** Replaces the EXDATE list for a master event. */
    @Query("update calendar.events set exdates = :exdates::jsonb, modified = now() where id = :id")
    suspend fun setExdates(id: UUID, exdates: JsonElement?)

    @Query("delete from calendar.events where id = :id")
    suspend fun deleteById(id: UUID)
}

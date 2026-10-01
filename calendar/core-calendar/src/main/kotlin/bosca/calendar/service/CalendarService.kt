package bosca.calendar.service

import bosca.calendar.model.Calendar
import bosca.calendar.model.CalendarEvent
import bosca.calendar.model.CalendarEventInput
import bosca.calendar.model.CalendarInput
import bosca.calendar.model.EventAttachment
import bosca.calendar.model.EventAttachmentInput
import bosca.calendar.model.EventOccurrence
import bosca.calendar.model.EventParticipant
import bosca.calendar.model.EventParticipantInput
import bosca.calendar.model.OccurrenceInput
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages calendars and events. A calendar hangs off a parent Metadata
 * record (contentType `bosca/v-calendar`); access is governed entirely by
 * the parent metadata's permissions, so a principal that can VIEW the
 * metadata can read the calendar and its events, EDIT permission gates
 * mutations, and MANAGE governs deletion.
 *
 * Recurring events follow RFC 5545 (iCalendar): a master event with an RRULE
 * defines a series, EXDATEs skip individual occurrences, and override events
 * (RECURRENCE-ID) replace individual occurrences with different details.
 * Range queries return [EventOccurrence] instances expanded from masters,
 * with exdates and overrides applied. Mutations come in three scopes:
 *
 *  * **This occurrence only** — creates/updates an override or appends to the
 *    master's EXDATE list.
 *  * **This and following** — splits the master at the chosen occurrence,
 *    setting UNTIL on the original and starting a new master at the boundary.
 *  * **All occurrences** — operates on the master row directly.
 */
interface CalendarService : Service {

    /**
     * Returns every calendar whose parent metadata the caller can VIEW.
     * Calendars on metadata the caller cannot read are silently filtered out.
     */
    suspend fun getAllCalendars(authentication: AuthenticationContext?): List<Calendar>

    /** Looks up a single calendar; null when missing or not visible. */
    suspend fun getCalendar(authentication: AuthenticationContext?, metadataId: UUID, version: Int): Calendar?

    /**
     * Creates the calendar row for an existing metadata. Idempotent on
     * duplicate calls. Requires EDIT on the parent metadata.
     */
    suspend fun createCalendar(
        authentication: AuthenticationContext,
        metadataId: UUID,
        version: Int,
        input: CalendarInput
    ): Calendar

    /** Updates the color and description of an existing calendar. Requires EDIT. */
    suspend fun editCalendar(
        authentication: AuthenticationContext,
        metadataId: UUID,
        version: Int,
        input: CalendarInput
    ): Calendar

    /**
     * Returns occurrences on a single calendar that overlap the half-open
     * range `[from, to)`. Recurring masters are expanded according to their
     * RRULE; EXDATEs and overrides are applied. Returns an empty list when
     * the principal lacks VIEW on the parent metadata.
     */
    suspend fun getOccurrences(
        authentication: AuthenticationContext?,
        metadataId: UUID,
        version: Int,
        from: OffsetDateTime,
        to: OffsetDateTime
    ): List<EventOccurrence>

    /**
     * Returns occurrences across the supplied calendars in one round trip.
     * Calendars whose metadata the caller cannot VIEW are silently skipped.
     */
    suspend fun getOccurrencesForCalendars(
        authentication: AuthenticationContext?,
        calendars: List<Pair<UUID, Int>>,
        from: OffsetDateTime,
        to: OffsetDateTime
    ): List<EventOccurrence>

    /** Loads a single stored event row by id; null when not found or not permitted. */
    suspend fun getEvent(authentication: AuthenticationContext?, id: UUID): CalendarEvent?

    /** Creates a new event; setting [CalendarEventInput.rrule] makes it a recurring master. */
    suspend fun addEvent(authentication: AuthenticationContext, input: CalendarEventInput): CalendarEvent

    /**
     * Updates an existing master or single event. Editing the rrule on a
     * master may invalidate existing overrides whose recurrence_id no longer
     * lines up with a generated occurrence; those overrides are pruned.
     */
    suspend fun editEvent(authentication: AuthenticationContext, id: UUID, input: CalendarEventInput): CalendarEvent

    /** Deletes a stored event row (master, single, or override). Cascades to overrides. */
    suspend fun deleteEvent(authentication: AuthenticationContext, id: UUID)

    /**
     * Edits a single occurrence of a recurring master. If an override already
     * exists at [recurrenceId] it is updated; otherwise a new override row is
     * created. Throws when [recurrenceId] is not actually a generated
     * occurrence of the master.
     */
    suspend fun editOccurrence(
        authentication: AuthenticationContext,
        masterId: UUID,
        recurrenceId: OffsetDateTime,
        input: OccurrenceInput
    ): CalendarEvent

    /**
     * Cancels a single occurrence. If an override exists at [recurrenceId]
     * it is deleted; in any case the [recurrenceId] is added to the master's
     * EXDATE list so future expansions skip it.
     */
    suspend fun cancelOccurrence(
        authentication: AuthenticationContext,
        masterId: UUID,
        recurrenceId: OffsetDateTime
    )

    /**
     * Splits the recurring series at [recurrenceId]: the original master gets
     * an UNTIL bound just before [recurrenceId], and a new master is created
     * starting at [recurrenceId] with the supplied input. Existing overrides
     * whose recurrence_id is at or after [recurrenceId] are reattached to the
     * new master. Returns the new master.
     */
    suspend fun splitSeriesAt(
        authentication: AuthenticationContext,
        masterId: UUID,
        recurrenceId: OffsetDateTime,
        input: CalendarEventInput
    ): CalendarEvent

    /**
     * Ends the series at [recurrenceId]: equivalent to [splitSeriesAt] without
     * a successor master. Sets UNTIL on the master so no occurrence at or
     * after [recurrenceId] is generated, and removes overrides in that range.
     */
    suspend fun endSeriesAt(
        authentication: AuthenticationContext,
        masterId: UUID,
        recurrenceId: OffsetDateTime
    ): CalendarEvent

    /** Returns all participants for the given event. */
    suspend fun getParticipants(authentication: AuthenticationContext?, eventId: UUID): List<EventParticipant>

    /** Adds or updates a participant on an event. Requires EDIT on the calendar. */
    suspend fun addParticipant(authentication: AuthenticationContext, input: EventParticipantInput): EventParticipant

    /** Removes a participant from an event. Requires EDIT on the calendar. */
    suspend fun removeParticipant(authentication: AuthenticationContext, eventId: UUID, profileId: UUID)

    /** Returns all attachments for the given event. */
    suspend fun getAttachments(authentication: AuthenticationContext?, eventId: UUID): List<EventAttachment>

    /** Links a metadata item or collection to an event. Requires EDIT on the calendar. */
    suspend fun addAttachment(authentication: AuthenticationContext, input: EventAttachmentInput): EventAttachment

    /** Removes an attachment link from an event. Requires EDIT on the calendar. */
    suspend fun removeAttachment(authentication: AuthenticationContext, attachmentId: UUID)
}

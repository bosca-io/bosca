package bosca.calendar.graphql

import bosca.calendar.model.Calendar
import bosca.calendar.model.CalendarEvent
import bosca.calendar.model.CalendarEventInput
import bosca.calendar.model.CalendarInput
import bosca.calendar.model.CalendarRef
import bosca.calendar.model.EventAttachment
import bosca.calendar.model.EventAttachmentInput
import bosca.calendar.model.EventParticipant
import bosca.calendar.model.EventParticipantInput
import bosca.calendar.model.OccurrenceInput
import bosca.calendar.service.CalendarService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

object CalendarsMutation

@TypeController
class CalendarsMutationController(
    private val calendarService: CalendarService
) : GraphQLController<CalendarsMutation> {

    @Field
    suspend fun addCalendar(
        authentication: AuthenticationContext,
        ref: CalendarRef,
        calendar: CalendarInput
    ): Calendar = calendarService.createCalendar(authentication, ref.metadataId, ref.version, calendar)

    @Field
    suspend fun editCalendar(
        authentication: AuthenticationContext,
        ref: CalendarRef,
        calendar: CalendarInput
    ): Calendar = calendarService.editCalendar(authentication, ref.metadataId, ref.version, calendar)

    @Field
    suspend fun addEvent(
        authentication: AuthenticationContext,
        event: CalendarEventInput
    ): CalendarEvent = calendarService.addEvent(authentication, event)

    @Field
    suspend fun editEvent(
        authentication: AuthenticationContext,
        id: UUID,
        event: CalendarEventInput
    ): CalendarEvent = calendarService.editEvent(authentication, id, event)

    @Field
    suspend fun deleteEvent(authentication: AuthenticationContext, id: UUID): Boolean {
        calendarService.deleteEvent(authentication, id)
        return true
    }

    @Field
    suspend fun editOccurrence(
        authentication: AuthenticationContext,
        masterId: UUID,
        recurrenceId: OffsetDateTime,
        occurrence: OccurrenceInput
    ): CalendarEvent = calendarService.editOccurrence(authentication, masterId, recurrenceId, occurrence)

    @Field
    suspend fun cancelOccurrence(
        authentication: AuthenticationContext,
        masterId: UUID,
        recurrenceId: OffsetDateTime
    ): Boolean {
        calendarService.cancelOccurrence(authentication, masterId, recurrenceId)
        return true
    }

    @Field
    suspend fun splitSeriesAt(
        authentication: AuthenticationContext,
        masterId: UUID,
        recurrenceId: OffsetDateTime,
        event: CalendarEventInput
    ): CalendarEvent = calendarService.splitSeriesAt(authentication, masterId, recurrenceId, event)

    @Field
    suspend fun endSeriesAt(
        authentication: AuthenticationContext,
        masterId: UUID,
        recurrenceId: OffsetDateTime
    ): CalendarEvent = calendarService.endSeriesAt(authentication, masterId, recurrenceId)

    @Field
    suspend fun addParticipant(
        authentication: AuthenticationContext,
        input: EventParticipantInput
    ): EventParticipant = calendarService.addParticipant(authentication, input)

    @Field
    suspend fun removeParticipant(
        authentication: AuthenticationContext,
        eventId: UUID,
        profileId: UUID
    ): Boolean {
        calendarService.removeParticipant(authentication, eventId, profileId)
        return true
    }

    @Field
    suspend fun addAttachment(
        authentication: AuthenticationContext,
        input: EventAttachmentInput
    ): EventAttachment = calendarService.addAttachment(authentication, input)

    @Field
    suspend fun removeAttachment(
        authentication: AuthenticationContext,
        id: UUID
    ): Boolean {
        calendarService.removeAttachment(authentication, id)
        return true
    }
}

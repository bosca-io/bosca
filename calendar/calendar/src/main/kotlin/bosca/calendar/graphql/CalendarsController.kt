package bosca.calendar.graphql

import bosca.calendar.model.Calendar
import bosca.calendar.model.CalendarEvent
import bosca.calendar.model.CalendarRef
import bosca.calendar.model.EventOccurrence
import bosca.calendar.repository.ScheduledContentEventRepository
import bosca.calendar.repository.ScheduledJobEventRepository
import bosca.calendar.repository.ScheduledPublishEventRepository
import bosca.calendar.service.CalendarService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

object Calendars

@TypeController
class CalendarsController(
    private val calendarService: CalendarService,
    private val scheduledContentEventRepository: ScheduledContentEventRepository,
    private val scheduledJobEventRepository: ScheduledJobEventRepository,
    private val scheduledPublishEventRepository: ScheduledPublishEventRepository,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Calendars> {

    @Field
    suspend fun all(authentication: AuthenticationContext?): List<Calendar> =
        calendarService.getAllCalendars(authentication)

    @Field
    suspend fun calendar(authentication: AuthenticationContext?, ref: CalendarRef): Calendar? =
        calendarService.getCalendar(authentication, ref.metadataId, ref.version)

    @Field
    suspend fun occurrences(
        authentication: AuthenticationContext?,
        from: OffsetDateTime,
        to: OffsetDateTime,
        calendars: List<CalendarRef>
    ): List<EventOccurrence> {
        if (calendars.isEmpty()) return emptyList()
        val pairs = calendars.map { it.metadataId to it.version }
        return calendarService.getOccurrencesForCalendars(authentication, pairs, from, to)
    }

    @Field
    suspend fun event(authentication: AuthenticationContext?, id: UUID): CalendarEvent? =
        calendarService.getEvent(authentication, id)

    @Field
    suspend fun scheduledContentEvents(
        authentication: AuthenticationContext,
        from: OffsetDateTime,
        to: OffsetDateTime
    ): List<SyntheticEventAndSource> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return scheduledContentEventRepository.getInRange(from, to).map {
            SyntheticEventAndSource(it, SyntheticEventSource.CAMPAIGN)
        }
    }

    @Field
    suspend fun scheduledJobEvents(
        authentication: AuthenticationContext,
        from: OffsetDateTime,
        to: OffsetDateTime
    ): List<SyntheticEventAndSource> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return scheduledJobEventRepository.getInRange(from, to).map {
            SyntheticEventAndSource(it, SyntheticEventSource.SCHEDULED_JOB)
        }
    }

    @Field
    suspend fun scheduledPublishEvents(
        authentication: AuthenticationContext,
        from: OffsetDateTime,
        to: OffsetDateTime
    ): List<SyntheticEventAndSource> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return scheduledPublishEventRepository.getInRange(from, to).map {
            SyntheticEventAndSource(it, SyntheticEventSource.SCHEDULED_PUBLISH)
        }
    }
}

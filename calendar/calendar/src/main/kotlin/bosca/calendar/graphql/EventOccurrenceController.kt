package bosca.calendar.graphql

import bosca.calendar.model.CalendarEvent
import bosca.calendar.model.EventOccurrence
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime

@TypeController(type = "EventOccurrence")
class EventOccurrenceController : GraphQLController<EventOccurrence> {

    @Field
    fun event(occurrence: EventOccurrence): CalendarEvent = occurrence.event

    @Field
    fun startsAt(occurrence: EventOccurrence): OffsetDateTime = occurrence.startsAt

    @Field
    fun endsAt(occurrence: EventOccurrence): OffsetDateTime = occurrence.endsAt

    @Field
    fun recurrenceId(occurrence: EventOccurrence): OffsetDateTime? = occurrence.recurrenceId

    @Field
    fun isRecurring(occurrence: EventOccurrence): Boolean = occurrence.isRecurring

    @Field
    fun isException(occurrence: EventOccurrence): Boolean = occurrence.isException
}

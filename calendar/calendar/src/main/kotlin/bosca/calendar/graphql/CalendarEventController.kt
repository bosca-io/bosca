package bosca.calendar.graphql

import bosca.calendar.model.Calendar
import bosca.calendar.model.CalendarEvent
import bosca.calendar.model.EventAttachment
import bosca.calendar.model.EventParticipant
import bosca.calendar.repository.CalendarRepository
import bosca.calendar.repository.EventAttachmentRepository
import bosca.calendar.repository.EventParticipantRepository
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@TypeController(type = "CalendarEvent")
class CalendarEventController(
    private val calendarRepository: CalendarRepository,
    private val participantRepository: EventParticipantRepository,
    private val attachmentRepository: EventAttachmentRepository
) : GraphQLController<CalendarEvent> {

    @Field
    fun id(event: CalendarEvent): UUID = event.id

    @Field
    fun title(event: CalendarEvent): String = event.title

    @Field
    fun description(event: CalendarEvent): String = event.description

    @Field
    fun location(event: CalendarEvent): String = event.location

    @Field
    fun allDay(event: CalendarEvent): Boolean = event.allDay

    @Field
    fun startsAt(event: CalendarEvent): OffsetDateTime = event.startsAt

    @Field
    fun endsAt(event: CalendarEvent): OffsetDateTime = event.endsAt

    @Field
    fun rrule(event: CalendarEvent): String? = event.rrule

    @Field
    fun exdates(event: CalendarEvent): List<OffsetDateTime> {
        val arr = event.exdates as? JsonArray ?: return emptyList()
        return arr.mapNotNull { entry ->
            val text = (entry as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            runCatching { OffsetDateTime.parse(text) }.getOrNull()
        }
    }

    @Field
    fun originalEventId(event: CalendarEvent): UUID? = event.originalEventId

    @Field
    fun recurrenceId(event: CalendarEvent): OffsetDateTime? = event.recurrenceId

    @Field
    fun created(event: CalendarEvent): OffsetDateTime = event.created

    @Field
    fun modified(event: CalendarEvent): OffsetDateTime = event.modified

    @Field
    suspend fun calendar(event: CalendarEvent): Calendar =
        calendarRepository.getById(event.metadataId, event.version)
            ?: error("Calendar not found for event ${event.id}")

    @Field
    suspend fun participants(event: CalendarEvent): List<EventParticipant> =
        participantRepository.getByEventId(event.id)

    @Field
    suspend fun attachments(event: CalendarEvent): List<EventAttachment> =
        attachmentRepository.getByEventId(event.id)
}

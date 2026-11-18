package bosca.design.model

import kotlinx.serialization.Serializable

/** A displayed occurrence. Applications expand recurrence and decide which events may be edited. */
@Serializable
data class CalendarEvent(
    val id: String,
    val title: String,
    val startsAt: String,
    val endsAt: String,
    val allDay: Boolean = false,
    val calendarId: String = "",
    val editable: Boolean = false,
    val description: String = "",
    val location: String = "",
    val participants: List<CalendarParticipant> = emptyList(),
    val rrule: String = "",
    val seriesId: String = "",
    val recurrenceId: String = "",
    val timeZone: String = "",

)

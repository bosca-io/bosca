package bosca.calendar.model

import kotlinx.serialization.Serializable

/**
 * Input for editing the display properties of an existing calendar. The
 * calendar's name comes from its parent metadata and is set there; this input
 * only carries fields stored on the calendar row itself.
 */
@Serializable
data class CalendarInput(
    val color: String = "#3b82f6",
    val description: String = ""
)

package bosca.calendar.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Input for creating or updating a stored event row (single event or recurring
 * master). The (metadataId, version) pair identifies the calendar to write to;
 * the caller must have EDIT permission on that metadata. To override a single
 * occurrence of a recurring master, use [OccurrenceInput] instead.
 */
@Serializable
data class CalendarEventInput(
    @Contextual
    val metadataId: UUID,
    val version: Int,
    val title: String,
    val description: String = "",
    val location: String = "",
    val allDay: Boolean = false,
    @Contextual
    val startsAt: OffsetDateTime,
    @Contextual
    val endsAt: OffsetDateTime,
    /**
     * Bare RRULE body, e.g. `FREQ=WEEKLY;BYDAY=MO`. Null creates or updates a
     * non-recurring event. Setting it on an existing single event promotes it
     * to a recurring master; clearing it on a master demotes back to a single
     * event and any overrides are removed by the cascade.
     */
    val rrule: String? = null
)

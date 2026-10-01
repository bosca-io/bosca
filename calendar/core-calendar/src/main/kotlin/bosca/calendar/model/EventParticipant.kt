package bosca.calendar.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A profile's participation in a calendar event, carrying an RFC 5545-inspired
 * role (organizer, attendee, etc.) and RSVP status (needs-action, accepted,
 * declined, tentative).
 */
@Serializable
data class EventParticipant(
    @ColumnName("event_id")
    @Contextual
    val eventId: UUID,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    val role: String = "attendee",
    val status: String = "needs-action",
    @Contextual
    val attributes: JsonElement? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now()
)

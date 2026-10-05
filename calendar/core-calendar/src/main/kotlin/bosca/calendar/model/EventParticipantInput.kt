package bosca.calendar.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Input for adding or updating a participant on a calendar event.
 */
@Serializable
data class EventParticipantInput(
    @Contextual
    val eventId: UUID,
    @Contextual
    val profileId: UUID,
    val role: String = "attendee",
    val status: String = "needs-action"
)

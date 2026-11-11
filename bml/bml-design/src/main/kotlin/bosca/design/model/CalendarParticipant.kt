package bosca.design.model

import kotlinx.serialization.Serializable

/** Application-supplied participant identity and attendance information. */
@Serializable
data class CalendarParticipant(val profileId: String, val profileName: String, val role: String = "attendee", val status: String = "pending")

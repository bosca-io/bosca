package bosca.chat.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Fires after a profile is invited to a chat channel. */
@JobEvent(
    jobs = [],
    displayName = "Chat Channel Invitation Sent",
    description = "Fires after a profile is invited to a chat channel.",
)
@Serializable
data class ChatChannelInvitationSentEvent(
    @Contextual val invitationId: UUID,
    @Contextual val channelId: UUID,
    @Contextual val inviterProfileId: UUID,
    @Contextual val inviteeProfileId: UUID,
    val role: String,
) : Event

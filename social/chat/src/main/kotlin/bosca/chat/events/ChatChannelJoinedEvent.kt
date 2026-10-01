package bosca.chat.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Fires whenever a profile becomes a channel member, regardless of how it joined. */
@JobEvent(
    jobs = [],
    displayName = "Chat Channel Joined",
    description = "Fires when a profile becomes a member of a chat channel.",
)
@Serializable
data class ChatChannelJoinedEvent(
    @Contextual val joinId: UUID,
    @Contextual val channelId: UUID,
    @Contextual val profileId: UUID,
    val role: String,
) : Event

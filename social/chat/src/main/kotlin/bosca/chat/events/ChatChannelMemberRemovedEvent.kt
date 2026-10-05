package bosca.chat.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

const val CHAT_CHANNEL_MEMBER_REMOVED_TOPIC = "bosca.chat.v1.channel.member.removed"

/** Fires after a profile loses membership in one concrete channel. */
@JobEvent(jobs = [], pubsubChannel = CHAT_CHANNEL_MEMBER_REMOVED_TOPIC)
@Serializable
data class ChatChannelMemberRemovedEvent(
    @Contextual val channelId: UUID,
    @Contextual val profileId: UUID,
) : Event

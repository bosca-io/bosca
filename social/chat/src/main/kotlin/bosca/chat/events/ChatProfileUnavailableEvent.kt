package bosca.chat.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

const val CHAT_PROFILE_UNAVAILABLE_TOPIC = "bosca.chat.v1.profile.unavailable"

/** Tells live chat subscriptions that their profile was hard-deleted or administratively unlinked. */
@JobEvent(jobs = [], pubsubChannel = CHAT_PROFILE_UNAVAILABLE_TOPIC)
@Serializable
data class ChatProfileUnavailableEvent(
    @Contextual val profileId: UUID,
) : Event

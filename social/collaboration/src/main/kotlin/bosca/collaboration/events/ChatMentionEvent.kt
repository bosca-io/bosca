package bosca.collaboration.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.communications.model.MessageContent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Dispatched when a chat message mentions one or more profiles that are
 * eligible to participate in chat but are not active channel members.
 * Triggers editable notification pipelines that deliver an email/push preview to each recipient.
 */
@JobEvent(
    jobs = [],
    displayName = "Chat Mention",
    description = "Fires when a chat message mentions eligible profiles outside the active channel.",
)
@Serializable
data class ChatMentionEvent(
    @Contextual
    val channelId: UUID,
    @Contextual
    val senderId: UUID,
    val sequence: Long,
    val recipientProfileIds: List<@Contextual UUID>,
    val senderName: String,
    val channelName: String,
    val content: List<MessageContent>,
) : Event

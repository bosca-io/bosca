package bosca.chat.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Dispatched after a profile adds a new reaction to an existing chat message. [reactionId]
 * identifies this specific add operation so retrying its notification pipeline remains idempotent.
 */
@JobEvent(
    jobs = [],
    displayName = "Chat Message Reaction Added",
    description = "Fires when a profile adds a reaction to an existing chat message.",
)
@Serializable
data class ChatMessageReactionAddedEvent(
    @Contextual
    val reactionId: UUID,
    @Contextual
    val channelId: UUID,
    val sequence: Long,
    @Contextual
    val reactorId: UUID,
    @Contextual
    val messageAuthorId: UUID,
    val emoji: String,
) : Event

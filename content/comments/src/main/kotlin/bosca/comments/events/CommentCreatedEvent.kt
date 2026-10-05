package bosca.comments.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Dispatched after a new comment is inserted. Carries the comment's coordinates so an
 * Event → Pipeline trigger can run a moderation pipeline (Get Comment → Moderate Text →
 * Evaluate Text Moderation → Set Comment Status). `@JobEvent` with no jobs still generates
 * `dispatch()`, which publishes the event to the pipeline dispatcher.
 */
@JobEvent
@Serializable
data class CommentCreatedEvent(
    @Contextual
    val metadataId: UUID,
    val metadataVersion: Int,
    val commentId: Long,
    @Contextual
    val profileId: UUID,
) : Event

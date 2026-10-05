package bosca.community.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/** The kind of positive activity a profile recorded on a prayer. */
@Serializable
enum class PrayerReactionType {
    PRAYED,
    LIKED,
}

/**
 * Fires after a profile newly records a positive reaction on a prayer. [activityId] identifies
 * this specific add operation so notification pipeline retries remain idempotent.
 */
@JobEvent(
    jobs = [],
    displayName = "Prayer Reaction Added",
    description = "Fires when a profile marks that they prayed for or liked a prayer.",
)
@Serializable
data class PrayerReactionAddedEvent(
    val activityId: UUID,
    val prayerId: UUID,
    val reactorId: UUID,
    val reaction: PrayerReactionType,
) : Event

/** Fires after a profile adds a top-level comment or reply to a prayer. */
@JobEvent(
    jobs = [],
    displayName = "Prayer Comment Added",
    description = "Fires when a profile adds a comment or reply to a prayer.",
)
@Serializable
data class PrayerCommentAddedEvent(
    val commentId: Long,
    val prayerId: UUID,
    val commenterId: UUID,
    val parentId: Long? = null,
) : Event

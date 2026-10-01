package bosca.profile.guide.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Records a profile's current guide progress after a step is added.
 *
 * [percentage] is the actual completion percentage. [percentageMilestone] is rounded down to the
 * nearest ten so automation criteria can use stable integer values such as 10, 20, or 50.
 * [firstAtPercentageMilestone] distinguishes the first event in that range from later events with
 * the same milestone.
 */
@JobEvent(
    jobs = [],
    displayName = "Guide Progress Added",
    description = "Fires when a profile records progress on a guide step."
)
@Serializable
class ProfileGuideProgressAdded(
    val profileId: UUID,
    val metadataId: UUID,
    val metadataVersion: Int,
    val stepId: Long,
    val initialProgress: Boolean,
    val percentage: Double = 0.0,
    val percentageMilestone: Int = 0,
    val firstAtPercentageMilestone: Boolean = false,
) : Event

@JobEvent(
    jobs = [],
    displayName = "Guide Completed",
    description = "Fires when a profile completes every step of a guide and its progress is moved to history."
)
@Serializable
class ProfileGuideCompleted(
    val profileId: UUID,
    val metadataId: UUID,
    val metadataVersion: Int,
) : Event

@JobEvent(
    jobs = [],
    displayName = "Guide Progress Deleted",
    description = "Fires when a profile's in-flight guide progress is removed or reset."
)
@Serializable
class ProfileGuideProgressDeleted(
    val profileId: UUID,
    val metadataId: UUID,
    val metadataVersion: Int,
) : Event

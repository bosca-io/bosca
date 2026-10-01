package bosca.profile.relationship.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/** Fires when a relationship is inserted, without implying a user-facing request workflow. */
@JobEvent(
    jobs = [],
    displayName = "Profile Relationship Added",
    description = "Fires when a relationship of the given type is created between two profiles."
)
@Serializable
class ProfileRelationshipAdded(
    val profileId1: UUID,
    val profileId2: UUID,
    val type: String,
) : Event

@JobEvent(
    jobs = [],
    displayName = "Profile Relationship Requested",
    description = "Fires when one profile requests a relationship with another profile."
)
@Serializable
class ProfileRelationshipRequested(
    val requestId: UUID,
    val requesterProfileId: UUID,
    val targetProfileId: UUID,
    val type: String,
) : Event

/** Records approval of [requestId] and drives its user-facing relationship notification. */
@JobEvent(
    jobs = [],
    displayName = "Profile Relationship Request Approved",
    description = "Fires when a profile relationship request is approved."
)
@Serializable
class ProfileRelationshipRequestApproved(
    val requestId: UUID,
    val profileId1: UUID,
    val profileId2: UUID,
    val type: String,
) : Event

@JobEvent(
    jobs = [],
    displayName = "Profile Relationship Removed",
    description = "Fires when a relationship of the given type is removed between two profiles."
)
@Serializable
class ProfileRelationshipRemoved(
    val profileId1: UUID,
    val profileId2: UUID,
    val type: String,
) : Event

package bosca.profile.mark.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@JobEvent(
    jobs = [],
    displayName = "Profile Mark Added",
    description = "Fires when a profile marks a content item or collection."
)
@Serializable
class ProfileMarkAdded(
    val profileId: UUID,
    val markId: Long,
    val metadataId: UUID?,
    val metadataVersion: Int?,
    val collectionId: UUID?,
) : Event

@JobEvent(
    jobs = [],
    displayName = "Profile Mark Deleted",
    description = "Fires when a profile mark is removed."
)
@Serializable
class ProfileMarkDeleted(
    val profileId: UUID,
    val markId: Long,
    val metadataId: UUID?,
    val metadataVersion: Int?,
    val collectionId: UUID?,
) : Event

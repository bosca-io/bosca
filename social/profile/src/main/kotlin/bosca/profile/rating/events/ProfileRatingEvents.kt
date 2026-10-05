package bosca.profile.rating.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@JobEvent(
    jobs = [],
    displayName = "Profile Rating Added",
    description = "Fires when a profile rates a content item or collection for the first time."
)
@Serializable
class ProfileRatingAdded(
    val profileId: UUID,
    val rating: Int,
    val metadataId: UUID?,
    val metadataVersion: Int?,
    val collectionId: UUID?,
) : Event

@JobEvent(
    jobs = [],
    displayName = "Profile Rating Updated",
    description = "Fires when a profile changes an existing rating; `previousRating` carries the replaced value."
)
@Serializable
class ProfileRatingUpdated(
    val profileId: UUID,
    val rating: Int,
    val previousRating: Int,
    val metadataId: UUID?,
    val metadataVersion: Int?,
    val collectionId: UUID?,
) : Event

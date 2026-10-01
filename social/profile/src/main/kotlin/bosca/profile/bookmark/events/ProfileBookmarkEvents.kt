package bosca.profile.bookmark.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@JobEvent(
    jobs = [],
    displayName = "Profile Bookmark Added",
    description = "Fires when a profile bookmarks a content item or collection."
)
@Serializable
class ProfileBookmarkAdded(
    val profileId: UUID,
    val metadataId: UUID?,
    val metadataVersion: Int?,
    val collectionId: UUID?,
) : Event

@JobEvent(
    jobs = [],
    displayName = "Profile Bookmark Deleted",
    description = "Fires when a profile removes a bookmark."
)
@Serializable
class ProfileBookmarkDeleted(
    val profileId: UUID,
    val metadataId: UUID?,
    val metadataVersion: Int?,
    val collectionId: UUID?,
) : Event

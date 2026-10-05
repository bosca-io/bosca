package bosca.calendar.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Input for linking a metadata item or collection to a calendar event.
 * Exactly one of [metadataId] or [collectionId] must be set.
 */
@Serializable
data class EventAttachmentInput(
    @Contextual
    val eventId: UUID,
    @Contextual
    val metadataId: UUID? = null,
    @Contextual
    val collectionId: UUID? = null,
    val relationship: String = "attachment"
)

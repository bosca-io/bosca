package bosca.content.timeevent.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Input for creating or updating a relationship between a time event
 * and a metadata item. Used when adding linked metadata references
 * to time events via GraphQL mutations.
 */
@Serializable
data class TimeEventMetadataRelationshipInput(
    @Contextual
    val metadataId: UUID,
    val metadataVersion: Int? = null,
    val relationship: String,
    @Contextual
    val attributes: JsonElement? = null
)

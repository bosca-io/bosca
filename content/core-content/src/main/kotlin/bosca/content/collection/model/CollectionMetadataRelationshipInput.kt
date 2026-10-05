package bosca.content.collection.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CollectionMetadataRelationshipInput(
    val id: UUID,
    val languageTag: String? = null,
    val metadataId: UUID,
    val relationship: String? = null,
    val attributes: JsonElement? = null
)
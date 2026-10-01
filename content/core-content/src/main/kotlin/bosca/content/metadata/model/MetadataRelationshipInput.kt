package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MetadataRelationshipInput(
    @Contextual
    val id1: UUID,
    @Contextual
    val id2: UUID,
    val relationship: String,
    @Contextual
    val attributes: JsonElement? = null,
)
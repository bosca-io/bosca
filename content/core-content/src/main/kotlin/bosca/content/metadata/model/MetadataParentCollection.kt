package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class MetadataParentCollection(
    @Contextual
    val id: UUID,
    @Contextual
    val attributes: JsonElement? = null
)
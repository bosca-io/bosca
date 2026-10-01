package bosca.content.collection.model

import bosca.content.metadata.model.MetadataInput
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MetadataChildInput(
    @Contextual
    val attributes: JsonElement? = null,
    val metadata: MetadataInput? = null
)
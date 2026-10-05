package bosca.content.collection.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CollectionChildInput(
    @Contextual
    val attributes: JsonElement? = null,
    val collection: CollectionInput? = null
)
package bosca.content.collection.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class CollectionParentCollection(
    @Contextual
    val id: UUID,
    @Contextual
    val attributes: JsonElement? = null
)
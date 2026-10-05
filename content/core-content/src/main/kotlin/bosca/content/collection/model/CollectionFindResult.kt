package bosca.content.collection.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CollectionFindResult(
    @Contextual
    val childCollectionId: UUID?,
    @Contextual
    val childMetadataId: UUID?,
    @Contextual
    val attributes: JsonElement?
)
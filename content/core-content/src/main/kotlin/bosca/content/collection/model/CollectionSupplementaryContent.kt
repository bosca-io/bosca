package bosca.content.collection.model

import kotlinx.serialization.Serializable

@Serializable
data class CollectionSupplementaryContent(
    val collection: Collection,
    val supplementary: CollectionSupplementary
)
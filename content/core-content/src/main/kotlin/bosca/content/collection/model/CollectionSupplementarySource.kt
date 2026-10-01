package bosca.content.collection.model

import kotlinx.serialization.Serializable

@Serializable
class CollectionSupplementarySource(
    val collection: Collection,
    val supplementary: CollectionSupplementary,
)
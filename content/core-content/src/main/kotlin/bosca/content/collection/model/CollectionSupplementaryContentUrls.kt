package bosca.content.collection.model

import kotlinx.serialization.Serializable

@Serializable
class CollectionSupplementaryContentUrls(
    val collection: Collection,
    val supplementary: CollectionSupplementary,
)
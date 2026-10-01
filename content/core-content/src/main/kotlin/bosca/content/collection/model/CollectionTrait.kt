package bosca.content.collection.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CollectionTrait(
    @ColumnName("collection_id")
    val collectionId: UUID,
    @ColumnName("trait_id")
    val traitId: String,
)
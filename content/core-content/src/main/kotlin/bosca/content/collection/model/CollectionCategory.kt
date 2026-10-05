package bosca.content.collection.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CollectionCategory(
    @ColumnName("collection_id")
    val collectionId: UUID,
    @ColumnName("category_id")
    val categoryId: UUID,
)
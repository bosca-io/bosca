package bosca.content.collection.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CollectionItem(
    val id: Long = 0,
    @ColumnName("collection_id")
    val collectionId: UUID,
    @ColumnName("child_collection_id")
    val childCollectionId: UUID? = null,
    @ColumnName("child_metadata_id")
    val childMetadataId: UUID? = null,
    @Contextual
    val attributes: JsonElement? = null
)
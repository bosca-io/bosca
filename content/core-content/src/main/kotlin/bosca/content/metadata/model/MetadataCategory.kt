package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class MetadataCategory(
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    @ColumnName("category_id")
    @Contextual
    val categoryId: UUID
)
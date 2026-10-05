package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class MetadataTrait(
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    @ColumnName("trait_id")
    val traitId: String
)
package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@BatchKey(type = MetadataCacheKeyId::class)
@Serializable
data class Data(
    @ColumnName("metadata_id")
    val metadataId: UUID,
    val version: Int,
    @ColumnName("template_metadata_id")
    val templateMetadataId: UUID? = null,
    @ColumnName("template_metadata_version")
    val templateMetadataVersion: Int? = null,
    val type: DataType = DataType.ATTRIBUTES
)

package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@BatchKey(type = MetadataCacheKeyId::class)
@Serializable
class GuideStepModule(
    val id: Long = 0,
    @ColumnName("metadata_id")
    @Contextual
    override val metadataId: UUID,
    override val version: Int,
    override val step: Long,
    @ColumnName("module_metadata_id")
    @Contextual
    val moduleMetadataId: UUID?,
    @ColumnName("module_metadata_version")
    val moduleMetadataVersion: Int?,
    val sort: Int
): MetadataCacheKeyable {

    override val key: String? = null
}
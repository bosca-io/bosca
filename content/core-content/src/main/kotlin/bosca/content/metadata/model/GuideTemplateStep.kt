package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@BatchKey(type = MetadataCacheKeyId::class)
@Serializable
class GuideTemplateStep(
    @ColumnName("metadata_id")
    @Contextual
    override val metadataId: UUID,
    override val version: Int,
    val id: Long = 0,
    @ColumnName("template_metadata_id")
    @Contextual
    val templateMetadataId: UUID?,
    @ColumnName("template_metadata_version")
    val templateMetadataVersion: Int?,
    val sort: Int
) : MetadataCacheKeyable {

    override val step: Long = id
    override val key: String? = null

}
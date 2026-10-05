package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@BatchKey(type = MetadataCacheKeyId::class)
@Serializable
class GuideTemplateStepModule(
    @Contextual
    override val metadataId: UUID,
    override val version: Int,
    override val step: Long,
    val id: Long? = null,
    @Contextual
    @ColumnName("template_metadata_id")
    val templateMetadataId: UUID?,
    @ColumnName("template_metadata_version")
    val templateMetadataVersion: Int?,
    val sort: Int
) : MetadataCacheKeyable {

    override val key: String? = null
}
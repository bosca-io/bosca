package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@BatchKey(type = MetadataCacheKeyId::class)
@Serializable
class GuideStep(
    val id: Long = 0,
    @ColumnName("metadata_id")
    @Contextual
    override val metadataId: UUID,
    override val version: Int,
    @ColumnName("step_metadata_id")
    @Contextual
    val stepMetadataId: UUID?,
    @ColumnName("step_metadata_version")
    val stepMetadataVersion: Int?,
    val sort: Int
) : MetadataCacheKeyable {

    override val key: String? = null
    override val step: Long? = null
}
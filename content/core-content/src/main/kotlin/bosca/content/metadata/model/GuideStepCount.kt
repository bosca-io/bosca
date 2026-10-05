package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class GuideStepCount(
    @ColumnName("metadata_id")
    @Contextual
    override val metadataId: UUID,
    override val version: Int,
    val count: Long
) : MetadataCacheKeyable {

    override val step: Long? = null
    override val key: String? = null
}

package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

@BatchKey(type = MetadataCacheKeyId::class)
@Serializable
class GuideTemplate(
    @Contextual
    @ColumnName("metadata_id")
    override val metadataId: UUID,
    override val version: Int,
    val rrule: String,
    val type: GuideType,
    @Contextual
    val configuration: JsonElement? = null,
    @ColumnName("default_attributes")
    @Contextual
    val defaultAttributes: JsonElement? = null
) : MetadataCacheKeyable {

    override val key: String? = null
    override val step: Long? = null

}
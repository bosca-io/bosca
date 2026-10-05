package bosca.content.metadata.model

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@BatchKey(type = MetadataCacheKeyId::class)
@Serializable
class GuideTemplateAttribute(
    @Contextual
    @ColumnName("metadata_id")
    override val metadataId: UUID,
    override val version: Int,
    override val key: String,
    val name: String,
    val description: String,
    @ColumnName("supplementary_key")
    val supplementaryKey: String? = null,
    @Contextual
    val configuration: JsonElement? = null,
    val type: AttributeType = AttributeType.STRING,
    val ui: AttributeUiType = AttributeUiType.INPUT,
    val list: Boolean = false,
    val sort: Int = 0,
    @Contextual
    val tools: JsonElement? = null,
) : MetadataCacheKeyable {

    override val step: Long? = null

}
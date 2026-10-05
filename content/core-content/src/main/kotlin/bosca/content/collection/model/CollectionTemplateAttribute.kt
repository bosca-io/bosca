package bosca.content.collection.model

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.content.attributes.model.TemplateTool
import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CollectionTemplateAttribute(
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID,
    val version: Int,
    val key: String,
    val name: String,
    val description: String,
    @ColumnName("supplementary_key")
    val supplementaryKey: String? = null,
    @Contextual
    val configuration: JsonElement? = null,
    val type: AttributeType,
    val ui: AttributeUiType,
    val list: Boolean = false,
    val sort: Int = 0,
    val location: AttributeLocation? = AttributeLocation.ITEM,
    @Contextual
    val tools: JsonElement? = null,
)
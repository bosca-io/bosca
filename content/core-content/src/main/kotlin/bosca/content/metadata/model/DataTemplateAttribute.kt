package bosca.content.metadata.model

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class DataTemplateAttribute(
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    val version: Int,
    val key: String,
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
)
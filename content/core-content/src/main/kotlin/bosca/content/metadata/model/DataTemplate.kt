package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class DataTemplate(
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID,
    val version: Int,
    val type: DataType = DataType.ATTRIBUTES,
    @Contextual
    @ColumnName("default_attributes")
    val defaultAttributes: JsonElement? = null
)
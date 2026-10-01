package bosca.content.timeevent.model

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.db.annotation.ColumnName
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * An attribute definition within a time event type, specifying the data type,
 * UI presentation, and validation configuration for a single field that events
 * of this type can carry. Follows the same pattern as data template attributes
 * to provide a consistent attribute editing experience.
 */
@Serializable
data class TimeEventTypeAttribute(
    @ColumnName("type_id")
    val typeId: String,
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

package bosca.attributes

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TemplateAttributeInput(
    val key: String,
    val name: String,
    val description: String,
    val supplementaryKey: String? = null,
    @Contextual
    val configuration: JsonElement? = null,
    val type: AttributeType,
    val ui: AttributeUiType,
    val list: Boolean = false,
    val location: AttributeLocation = AttributeLocation.ITEM,
    val workflows: List<TemplateWorkflowInput>? = null,
    val tools: List<TemplateToolInput>? = null,
)

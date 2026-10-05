package bosca.content.tools.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TemplateAttributeToolInput(
    val key: String,
    val name: String,
    val description: String? = null,
    val query: String,
    val resultPath: String? = null,
    val configuration: JsonElement? = null
)

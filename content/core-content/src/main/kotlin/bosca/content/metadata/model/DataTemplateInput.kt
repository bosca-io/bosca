package bosca.content.metadata.model

import bosca.attributes.TemplateAttributeInput
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class DataTemplateInput(
    val type: DataType? = null,
    @Contextual
    val defaultAttributes: JsonElement? = null,
    val attributes: List<TemplateAttributeInput> = listOf()
)
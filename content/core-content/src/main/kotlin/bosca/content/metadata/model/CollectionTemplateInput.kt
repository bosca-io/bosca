package bosca.content.metadata.model

import bosca.attributes.TemplateAttributeInput
import bosca.content.ordering.OrderingInput
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CollectionTemplateInput(
    val attributes: List<TemplateAttributeInput> = listOf(),
    @Contextual
    val defaultAttributes: JsonElement? = null,
    val filters: CollectionTemplateFilters? = null,
    val ordering: List<OrderingInput> = listOf(),
    @Contextual
    val configuration: JsonElement? = null
)
package bosca.content.metadata.model

import bosca.attributes.TemplateAttributeInput
import bosca.documents.Content
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class DocumentTemplateInput(
    @Contextual
    val configuration: JsonElement? = null,
    @Contextual
    val schema: JsonElement? = null,
    @Contextual
    val content: Content? = null,
    @Contextual
    val defaultAttributes: JsonElement? = null,
    val attributes: List<TemplateAttributeInput> = listOf(),
    val containers: List<DocumentTemplateContainerInput> = listOf()
)
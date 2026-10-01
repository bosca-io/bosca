package bosca.content.metadata.model

import bosca.attributes.TemplateToolInput
import kotlinx.serialization.Serializable

@Serializable
class DocumentTemplateContainerInput(
    val id: String,
    val name: String,
    val description: String,
    val supplementaryKey: String? = null,
    val containerType: ContainerType? = ContainerType.STANDARD,
    val tools: List<TemplateToolInput>? = null,
    val renderers: List<ContainerRendererInput>? = null,
    val filters: List<String>? = null,
)

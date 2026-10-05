package bosca.attributes

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class TemplateToolInput(
    @Contextual
    val id: UUID? = null,
    val name: String? = null,
    val description: String? = null,
    val query: String? = null,
    val resultPath: String? = null,
)

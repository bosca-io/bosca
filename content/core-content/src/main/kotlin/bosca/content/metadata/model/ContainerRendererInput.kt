package bosca.content.metadata.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ContainerRendererInput(
    val name: String,
    val configuration: JsonElement? = null
)

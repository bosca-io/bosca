package bosca.source.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SourceInput(
    val name: String,
    val description: String,
    @Contextual
    val configuration: JsonElement
)

package bosca.ai.models.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ModelInput(
    val key: String,
    val name: String,
    val description: String,
    val type: String,
    @Contextual
    val configuration: JsonElement?,
)

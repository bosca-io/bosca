package bosca.content.transition.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TransitionInput(
    val description: String,
    val fromStateId: String,
    val toStateId: String,
    val enterJobName: String?,
    val exitJobName: String?,
    val configuration: JsonElement?
)

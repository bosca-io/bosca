package bosca.content.state.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class StateInput(
    val id: String,
    val name: String,
    val description: String,
    val type: WorkflowStateType,
    val configuration: JsonElement,
    val jobName: String? = null
)

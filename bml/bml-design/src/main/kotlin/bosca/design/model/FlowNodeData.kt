package bosca.design.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Shared node/card presentation. Null port lists retain VueFlow's default handle; empty lists remove it. */
@Serializable
data class FlowNodeData(
    val label: String? = null,
    val description: String? = null,
    val status: String? = null,
    val tone: String? = null,
    val inputs: List<FlowPort>? = null,
    val outputs: List<FlowPort>? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
)

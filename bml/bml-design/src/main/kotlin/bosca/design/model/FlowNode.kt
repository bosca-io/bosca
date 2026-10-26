package bosca.design.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/** A VueFlow node with shared presentation and caller-owned data; transient engine fields are ignored. */
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
@Serializable
data class FlowNode(
    val id: String,
    @EncodeDefault
    val position: FlowPosition = FlowPosition(),
    @EncodeDefault
    val type: String = "default",
    @EncodeDefault
    val data: FlowNodeData = FlowNodeData(),
    val sourcePosition: FlowSide? = null,
    val targetPosition: FlowSide? = null,
    val draggable: Boolean? = null,
    val connectable: Boolean? = null,
    val selectable: Boolean? = null,
    val focusable: Boolean? = null,
    val hidden: Boolean? = null,
)

package bosca.design.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/** A connection between node IDs and optional named handles; transient engine fields are ignored. */
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
@Serializable
data class FlowEdge(
    val id: String,
    val source: String,
    val target: String,
    val sourceHandle: String? = null,
    val targetHandle: String? = null,
    val type: String? = null,
    val label: String? = null,
    val animated: Boolean? = null,
    val style: FlowEdgeStyle? = null,
    val markerStart: FlowMarker? = null,
    val markerEnd: FlowMarker? = null,
)

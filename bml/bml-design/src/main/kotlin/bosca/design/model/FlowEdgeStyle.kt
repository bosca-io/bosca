package bosca.design.model

import kotlinx.serialization.Serializable

/** Common SVG edge stroke settings. */
@Serializable
data class FlowEdgeStyle(
    val stroke: String? = null,
    val strokeWidth: Double? = null,
    val strokeDasharray: String? = null,
    val opacity: Double? = null,
)

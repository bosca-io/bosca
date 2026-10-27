package bosca.design.model

import kotlinx.serialization.Serializable

/** Common tooltip behavior and colors. */
@Serializable
data class ChartTooltip(
    val enabled: Boolean? = null,
    val mode: ChartInteractionMode? = null,
    val intersect: Boolean? = null,
    val backgroundColor: String? = null,
    val titleColor: String? = null,
    val bodyColor: String? = null,
)

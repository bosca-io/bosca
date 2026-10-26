package bosca.design.model

import kotlinx.serialization.Serializable

/** Common legend label settings. */
@Serializable
data class ChartLegendLabels(
    val color: String? = null,
    val boxWidth: Double? = null,
    val usePointStyle: Boolean? = null,
)

package bosca.design.model

import kotlinx.serialization.Serializable

/** Legend visibility, position and label presentation. */
@Serializable
data class ChartLegend(
    val display: Boolean? = null,
    val position: ChartLegendPosition? = null,
    val labels: ChartLegendLabels? = null,
)

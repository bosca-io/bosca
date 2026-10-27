package bosca.design.model

import kotlinx.serialization.Serializable

/** A Cartesian or radial axis; type stays open for registered scale implementations. */
@Serializable
data class ChartScale(
    val type: String? = null,
    val beginAtZero: Boolean? = null,
    val min: Double? = null,
    val max: Double? = null,
    val stacked: Boolean? = null,
    val title: ChartTitle? = null,
    val ticks: ChartTicks? = null,
    val grid: ChartGrid? = null,
)

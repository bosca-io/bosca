package bosca.design.model

import kotlinx.serialization.Serializable

/** Built-in chart plugin settings. */
@Serializable
data class ChartPlugins(
    val legend: ChartLegend? = null,
    val tooltip: ChartTooltip? = null,
    val title: ChartTitle? = null,
)

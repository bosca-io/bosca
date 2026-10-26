package bosca.design.model

import kotlinx.serialization.Serializable

/** Common axis grid settings. */
@Serializable
data class ChartGrid(
    val display: Boolean? = null,
    val color: String? = null,
    val drawOnChartArea: Boolean? = null,
)

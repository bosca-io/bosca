package bosca.design.model

import kotlinx.serialization.Serializable

/** Common axis tick settings. */
@Serializable
data class ChartTicks(
    val color: String? = null,
    val stepSize: Double? = null,
    val maxTicksLimit: Int? = null,
)

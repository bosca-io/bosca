package bosca.design.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Chart.js series values with structured points and styling. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChartDataset(
    val label: String? = null,
    @EncodeDefault
    val data: List<ChartValue> = emptyList(),
    val backgroundColor: String? = null,
    val borderColor: String? = null,
    val borderWidth: Double? = null,
    val borderRadius: Double? = null,
    val tension: Double? = null,
    val fill: Boolean? = null,
    val pointRadius: Double? = null,
    val stack: String? = null,
    val xAxisID: String? = null,
    val yAxisID: String? = null,
    val hidden: Boolean? = null,
    val type: ChartType? = null,
) {
    constructor(label: String? = null, data: Collection<Number>, backgroundColor: String? = null, borderColor: String? = null,
                borderWidth: Double? = null, stack: String? = null) :
        this(label, data.map { ChartValue.Number(it.toDouble()) }, backgroundColor, borderColor, borderWidth, stack = stack)
}

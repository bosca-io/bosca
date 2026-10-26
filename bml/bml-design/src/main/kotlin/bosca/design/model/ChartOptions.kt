package bosca.design.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Common chart behavior and named axes; axes use native Chart.js names. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChartOptions(
    val responsive: Boolean? = null,
    val maintainAspectRatio: Boolean? = null,
    val animation: Boolean? = null,
    val plugins: ChartPlugins? = null,
    @EncodeDefault
    val scales: Map<String, ChartScale> = emptyMap(),
)

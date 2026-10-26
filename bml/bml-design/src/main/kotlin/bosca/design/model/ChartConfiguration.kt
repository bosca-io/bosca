package bosca.design.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Chart.js configuration with typed settings. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChartConfiguration(
    val type: ChartType,
    @EncodeDefault
    val data: ChartData = ChartData(),
    @EncodeDefault
    val options: ChartOptions = ChartOptions(),
)

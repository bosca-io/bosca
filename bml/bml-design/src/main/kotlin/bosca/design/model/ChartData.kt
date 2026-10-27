package bosca.design.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Chart labels and series; JSON labels also support numbers and multiline labels. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChartData(
    @EncodeDefault
    val labels: JsonArray = JsonArray(emptyList()),
    @EncodeDefault
    val datasets: List<ChartDataset> = emptyList(),
) {
    constructor(labels: List<String>, datasets: List<ChartDataset>) : this(JsonArray(labels.map(::JsonPrimitive)), datasets)
}

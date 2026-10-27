package bosca.design.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Server-rendered chart data remains readable before and without client rendering. */
data class ChartTable(val points: List<ChartPoint>, val error: String = "") {
    companion object {
        /** Read ordinary Chart.js labels and array datasets without executing configuration code. */
        fun from(configuration: ChartConfiguration): ChartTable =
            ChartTable(configuration.data.datasets.flatMapIndexed { datasetIndex, dataset ->
                val series = dataset.label ?: "Series ${datasetIndex + 1}"
                dataset.data.mapIndexed { index, point ->
                    ChartPoint(datasetIndex, index, series, configuration.data.labels.getOrNull(index)?.let(::display) ?: "${index + 1}", display(Json.encodeToJsonElement(ChartValue.serializer(), point)))
                }
            })

        private fun display(value: JsonElement): String = when (value) {
            JsonNull -> "No value"
            is JsonPrimitive -> value.content
            is JsonObject -> value.entries.joinToString(", ") { (key, item) -> "$key: ${display(item)}" }
            is JsonArray -> value.joinToString(", ", transform = ::display)
        }
    }
}

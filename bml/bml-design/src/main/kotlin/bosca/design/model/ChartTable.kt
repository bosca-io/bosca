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
        fun from(configuration: String): ChartTable = try {
            val root = Json.parseToJsonElement(configuration) as? JsonObject
            require(root != null) { "Chart configuration must be an object." }
            val data = root["data"] as? JsonObject
            val labels = data?.get("labels") as? JsonArray
            val datasets = data?.get("datasets") as? JsonArray
            ChartTable(datasets.orEmpty().flatMapIndexed { datasetIndex, element ->
                val dataset = element as? JsonObject
                val series = (dataset?.get("label") as? JsonPrimitive)?.content ?: "Series ${datasetIndex + 1}"
                (dataset?.get("data") as? JsonArray).orEmpty().mapIndexed { index, point ->
                    ChartPoint(datasetIndex, index, series, labels?.getOrNull(index)?.let(::display) ?: "${index + 1}", display(point))
                }
            })
        } catch (exception: IllegalArgumentException) {
            ChartTable(emptyList(), exception.message ?: "Invalid chart configuration.")
        }

        private fun display(value: JsonElement): String = when (value) {
            JsonNull -> "No value"
            is JsonPrimitive -> value.content
            is JsonObject -> value.entries.joinToString(", ") { (key, item) -> "$key: ${display(item)}" }
            is JsonArray -> value.joinToString(", ", transform = ::display)
        }
    }
}

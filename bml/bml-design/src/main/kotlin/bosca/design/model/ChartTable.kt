package bosca.design.model

import kotlinx.serialization.json.*

/** Server-rendered inline series data remains readable without client rendering. */
data class ChartTable(val points: List<ChartPoint>, val error: String = "") {
    companion object {
        /** Read native ECharts series data without evaluating formatters or transforms. */
        fun from(configuration: ChartConfiguration): ChartTable {
            val option = configuration.option
            val series = objects(option["series"])
            return ChartTable(series.flatMapIndexed { seriesIndex, item ->
                val name = (item["name"] as? JsonPrimitive)?.content ?: "Series ${seriesIndex + 1}"
                val axis = objects(option["xAxis"]).getOrNull((item["xAxisIndex"] as? JsonPrimitive)?.intOrNull ?: 0)
                    ?: objects(option["yAxis"]).firstOrNull()
                val labels = axis?.get("data") as? JsonArray
                (item["data"] as? JsonArray).orEmpty().mapIndexed { index, point ->
                    val objectPoint = point as? JsonObject
                    val label = objectPoint?.get("name") ?: labels?.getOrNull(index) ?: JsonPrimitive(index + 1)
                    ChartPoint(seriesIndex, index, name, display(label), display(objectPoint?.get("value") ?: point))
                }
            })
        }

        private fun objects(value: JsonElement?): List<JsonObject> = when (value) {
            is JsonObject -> listOf(value)
            is JsonArray -> value.filterIsInstance<JsonObject>()
            else -> emptyList()
        }

        private fun display(value: JsonElement): String = when (value) {
            JsonNull -> "No value"
            is JsonPrimitive -> value.content
            is JsonObject -> value.entries.joinToString(", ") { (key, item) -> "$key: ${display(item)}" }
            is JsonArray -> value.joinToString(", ", transform = ::display)
        }
    }
}

package bosca.design.catalog

import bosca.design.model.*
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable

/** Deterministic chart data and local presentation states, without analytics queries. */
@Serializable
data class CatalogChart(
    val id: String,
    var type: String = "bar",
    var alternate: Boolean = false,
    var loading: Boolean = false,
    var failed: Boolean = false,
    var empty: Boolean = false,
    var visible: Boolean = true,
    var notice: String = "",
    var noticeSequence: Int = 0,
) {
    val config: ChartConfiguration get() = configuration(type, alternate, empty)
    val maps: Map<String, JsonObject> get() = if (type == "map") mapOf("world" to worldMap) else emptyMap()
    val label: String get() = "${id.replaceFirstChar { it.titlecase() }} palette visits"
    fun chooseType(value: String) { require(value in types); type = value }
    fun replace() { alternate = !alternate }
    fun refresh() { notice = "Example refreshed."; noticeSequence++ }
    fun toggleLoading() { loading = !loading }
    fun toggleFailure() { failed = !failed }
    fun toggleEmpty() { empty = !empty }
    fun toggleVisible() { visible = !visible }
    fun selected(dataset: Int, index: Int) { notice = "Selected series ${dataset + 1}, point ${index + 1}."; noticeSequence++ }

    companion object {
        val types = listOf("bar", "line", "scatter", "bubble", "pie", "doughnut", "radar", "polarArea", "heatmap", "funnel", "gauge", "map")
        val worldMap: JsonObject by lazy {
            val source = requireNotNull(CatalogChart::class.java.getResourceAsStream("/maps/world.geojson")) { "Missing catalog world boundaries." }
            source.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        }
        val mapRegions = listOf("United States of America", "Canada", "Mexico", "Brazil", "United Kingdom", "France",
            "Germany", "India", "China", "Australia", "Japan", "South Africa")
        fun configuration(type: String, alternate: Boolean = false, empty: Boolean = false): ChartConfiguration {
            require(type in types)
            val seriesType = when (type) { "bubble" -> "scatter"; "doughnut", "polarArea" -> "pie"; else -> type }
            val labels = listOf("Mon", "Tue", "Wed")
            val values = if (empty) emptyList() else if (alternate) listOf(8, 3, 12) else listOf(4, 7, 5)
            return ChartConfiguration(buildJsonObject {
                put("animation", false)
                putJsonObject("legend") { put("bottom", 0); if (type == "map") put("show", false) }
                putJsonObject("tooltip") { put("trigger", "item") }
                if (type in listOf("bar", "line", "scatter", "bubble", "heatmap")) {
                    putJsonObject("grid") { put("left", 45); put("right", 24); put("top", 24); put("bottom", 55) }
                    putJsonObject("xAxis") {
                        put("type", if (type in listOf("scatter", "bubble")) "value" else "category")
                        if (type !in listOf("scatter", "bubble")) putJsonArray("data") { labels.forEach { add(it) } }
                    }
                    putJsonObject("yAxis") {
                        put("type", if (type == "heatmap") "category" else "value")
                        if (type == "heatmap") putJsonArray("data") { add("Visits") }
                    }
                }
                if (type == "map") putJsonObject("visualMap") {
                    put("min", 0); put("max", 100); put("orient", "horizontal"); put("left", "center"); put("bottom", 0)
                    putJsonArray("text") { add("More visits"); add("Fewer visits") }
                    putJsonObject("inRange") { putJsonArray("color") { add("#dbeafe"); add("#2563eb") } }
                }
                if (type == "heatmap") putJsonObject("visualMap") { put("min", 0); put("max", 15); put("show", false) }
                if (type == "radar") putJsonObject("radar") {
                    putJsonArray("indicator") { labels.forEach { label -> add(buildJsonObject { put("name", label); put("max", 15) }) } }
                }
                putJsonArray("series") { add(buildJsonObject {
                    put("name", "Visits"); put("type", seriesType)
                    if (type == "map") {
                        put("map", "world"); put("roam", true); put("selectedMode", "single")
                        put("preserveAspect", true); put("aspectScale", 1); put("showLegendSymbol", false)
                        put("left", 8); put("right", 8); put("top", 12); put("bottom", 45)
                        putJsonObject("scaleLimit") { put("min", 0.7); put("max", 8) }
                        putJsonObject("select") { putJsonObject("itemStyle") { put("areaColor", "#f59e0b") } }
                    }
                    if (type == "doughnut") putJsonArray("radius") { add("35%"); add("65%") }
                    if (type == "polarArea") put("roseType", "area")
                    if (type == "gauge") putJsonObject("detail") { put("formatter", "{value}") }
                    putJsonArray("data") {
                        if (type == "map") {
                            if (!empty) mapRegions.forEachIndexed { index, name -> add(buildJsonObject {
                                put("name", name); put("value", if (alternate) (index * 17 + 31) % 100 else (index * 11 + 13) % 100)
                            }) }
                        } else if (type == "radar" && values.isNotEmpty()) add(buildJsonObject {
                            put("name", "Visits"); putJsonArray("value") { values.forEach { add(it) } }
                        }) else values.forEachIndexed { index, value ->
                            when (type) {
                                "scatter" -> add(buildJsonArray { add(index + 1); add(value) })
                                "bubble" -> add(buildJsonObject {
                                    put("name", labels[index]); put("symbolSize", listOf(16, 24, 20)[index])
                                    putJsonArray("value") { add(index + 1); add(value) }
                                })
                                "heatmap" -> add(buildJsonArray { add(index); add(0); add(value) })
                                else -> add(buildJsonObject { put("name", labels[index]); put("value", value) })
                            }
                        }
                    }
                }) }
            })
        }
    }
}

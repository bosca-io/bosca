package bosca.workops.ui

import bosca.design.model.ChartConfiguration
import kotlinx.serialization.json.*
import java.time.Instant

/** Native shared-chart options preserve the metric's reported timestamps and numeric values. */
internal fun workOpsHealthChart(name: String, points: List<Pair<Instant, Number>>, percent: Boolean = false, color: String = "var(--accent)") = ChartConfiguration(buildJsonObject {
    putJsonObject("grid") { put("left", 48); put("right", 16); put("top", 16); put("bottom", 32) }
    putJsonObject("xAxis") { put("type", "time"); put("splitNumber", 4); putJsonObject("axisLabel") { put("hideOverlap", true) } }
    putJsonObject("yAxis") { put("type", "value"); if (percent) putJsonObject("axisLabel") { put("formatter", "{value}%") } }
    putJsonObject("tooltip") { put("trigger", "axis") }
    putJsonArray("series") { add(buildJsonObject {
        put("name", name); put("type", "line"); put("showSymbol", points.size < 2)
        putJsonObject("lineStyle") { put("color", color) }; putJsonObject("itemStyle") { put("color", color) }
        putJsonArray("data") { for ((time, value) in points.sortedBy { it.first }) add(buildJsonObject {
            put("name", time.toString()); put("value", JsonArray(listOf(JsonPrimitive(time.toString()), JsonPrimitive(value))))
        }) }
    }) }
})

/** A rate is known only where all three response classes reported the same instant. */
internal fun workOpsHttpRateSamples(success: WorkOpsHealthCounter?, client: WorkOpsHealthCounter?, server: WorkOpsHealthCounter?): List<Pair<Instant, Number>> {
    if (success == null || client == null || server == null) return emptyList()
    val good = success.samples.associate { it.instant to it.value }
    val clientErrors = client.samples.associate { it.instant to it.value }
    val serverErrors = server.samples.associate { it.instant to it.value }
    return good.keys.intersect(clientErrors.keys).intersect(serverErrors.keys).sorted().map { time ->
        val errors = clientErrors.getValue(time) + serverErrors.getValue(time)
        val total = good.getValue(time) + errors
        time to if (total == 0L) 0.0 else errors.toDouble() / total * 100
    }
}

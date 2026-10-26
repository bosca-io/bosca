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
        val types = listOf("bar", "line", "scatter", "bubble", "pie", "doughnut", "radar", "polarArea")
        fun configuration(type: String, alternate: Boolean = false, empty: Boolean = false): ChartConfiguration {
            val chartType = ChartType.fromValue(type)
            val values: List<ChartValue> = when {
                empty -> emptyList()
                chartType == ChartType.SCATTER -> listOf(4, 7, 5).mapIndexed { index, y -> ChartValue.Scatter(index + 1.0, y.toDouble()) }
                chartType == ChartType.BUBBLE -> listOf(4, 7, 5).mapIndexed { index, y -> ChartValue.Bubble(index + 1.0, y.toDouble(), listOf(8.0, 12.0, 10.0)[index]) }
                else -> (if (alternate) listOf(8, 3, 12) else listOf(4, 7, 5)).map { ChartValue.Number(it.toDouble()) }
            }
            return ChartConfiguration(chartType, ChartData(listOf("Mon", "Tue", "Wed"), listOf(ChartDataset("Visits", values))),
                options = ChartOptions(plugins = ChartPlugins(legend = ChartLegend(position = ChartLegendPosition.BOTTOM))))
        }
    }
}

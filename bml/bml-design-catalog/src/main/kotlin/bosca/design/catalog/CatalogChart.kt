package bosca.design.catalog

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
    val config: String get() = configuration(type, alternate, empty)
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
        fun configuration(type: String, alternate: Boolean = false, empty: Boolean = false): String {
            val values = when {
                empty -> "[]"
                type == "scatter" -> """[{"x":1,"y":4},{"x":2,"y":7},{"x":3,"y":5}]"""
                type == "bubble" -> """[{"x":1,"y":4,"r":8},{"x":2,"y":7,"r":12},{"x":3,"y":5,"r":10}]"""
                alternate -> "[8,3,12]"
                else -> "[4,7,5]"
            }
            return """{"type":"$type","data":{"labels":["Mon","Tue","Wed"],"datasets":[{"label":"Visits","data":$values}]},"options":{"plugins":{"legend":{"position":"bottom"}}}}"""
        }
    }
}

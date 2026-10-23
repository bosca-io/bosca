package bosca.design.model

/** One data point in a chart's server-rendered text alternative. */
data class ChartPoint(val dataset: Int, val index: Int, val series: String, val label: String, val value: String)

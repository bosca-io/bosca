package bosca.design.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Chart.js values, including gaps and application-defined points. */
@Serializable(with = ChartValueSerializer::class)
sealed interface ChartValue {
    data class Number(val value: Double) : ChartValue {
        init { require(value.isFinite()) { "Chart values must be finite; use Gap for missing values." } }
    }
    data class Scatter(val x: Double, val y: Double) : ChartValue {
        init { require(x.isFinite() && y.isFinite()) }
    }
    data class Bubble(val x: Double, val y: Double, val r: Double) : ChartValue {
        init { require(x.isFinite() && y.isFinite() && r.isFinite() && r >= 0) }
    }
    data object Gap : ChartValue
    /** Data for custom parsing keys, ranges or an application-owned chart controller. */
    data class Custom(val value: JsonElement) : ChartValue
}

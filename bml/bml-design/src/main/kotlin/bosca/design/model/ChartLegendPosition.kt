package bosca.design.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Supported chart legend position values and their browser spelling. */
@Serializable
enum class ChartLegendPosition(val value: String) {
    @SerialName("top") TOP("top"),
    @SerialName("bottom") BOTTOM("bottom"),
    @SerialName("left") LEFT("left"),
    @SerialName("right") RIGHT("right"),
    @SerialName("chartArea") CHART_AREA("chartArea");

    override fun toString(): String = value

    companion object {
        /** Resolves an explicit native value, rejecting unknown choices. */
        fun fromValue(value: String): ChartLegendPosition = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("Unknown ChartLegendPosition: $value")
    }
}

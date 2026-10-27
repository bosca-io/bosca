package bosca.design.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Supported chart type values and their browser spelling. */
@Serializable
enum class ChartType(val value: String) {
    @SerialName("bar") BAR("bar"),
    @SerialName("line") LINE("line"),
    @SerialName("scatter") SCATTER("scatter"),
    @SerialName("bubble") BUBBLE("bubble"),
    @SerialName("pie") PIE("pie"),
    @SerialName("doughnut") DOUGHNUT("doughnut"),
    @SerialName("radar") RADAR("radar"),
    @SerialName("polarArea") POLAR_AREA("polarArea");

    override fun toString(): String = value

    companion object {
        /** Resolves an explicit native value, rejecting unknown choices. */
        fun fromValue(value: String): ChartType = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("Unknown ChartType: $value")
    }
}

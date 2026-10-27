package bosca.design.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Supported chart interaction mode values and their browser spelling. */
@Serializable
enum class ChartInteractionMode(val value: String) {
    @SerialName("point") POINT("point"),
    @SerialName("nearest") NEAREST("nearest"),
    @SerialName("index") INDEX("index"),
    @SerialName("dataset") DATASET("dataset"),
    @SerialName("x") X("x"),
    @SerialName("y") Y("y");

    override fun toString(): String = value

    companion object {
        /** Resolves an explicit native value, rejecting unknown choices. */
        fun fromValue(value: String): ChartInteractionMode = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("Unknown ChartInteractionMode: $value")
    }
}

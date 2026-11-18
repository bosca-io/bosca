package bosca.design.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Supported flow side values and their browser spelling. */
@Serializable
enum class FlowSide(val value: String) {
    @SerialName("top") TOP("top"),
    @SerialName("bottom") BOTTOM("bottom"),
    @SerialName("left") LEFT("left"),
    @SerialName("right") RIGHT("right");

    override fun toString(): String = value

    companion object {
        /** Resolves an explicit native value, rejecting unknown choices. */
        fun fromValue(value: String): FlowSide = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("Unknown FlowSide: $value")
    }
}

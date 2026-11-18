package bosca.design.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Supported flow marker type values and their browser spelling. */
@Serializable
enum class FlowMarkerType(val value: String) {
    @SerialName("arrow") ARROW("arrow"),
    @SerialName("arrowclosed") ARROW_CLOSED("arrowclosed");

    override fun toString(): String = value

    companion object {
        /** Resolves an explicit native value, rejecting unknown choices. */
        fun fromValue(value: String): FlowMarkerType = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("Unknown FlowMarkerType: $value")
    }
}

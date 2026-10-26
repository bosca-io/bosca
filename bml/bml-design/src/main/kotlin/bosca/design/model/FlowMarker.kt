package bosca.design.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** A built-in edge marker, or a reference to an application SVG marker. */
@Serializable(with = FlowMarkerSerializer::class)
sealed interface FlowMarker {
    data class Reference(val id: String) : FlowMarker
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Arrow(
        @EncodeDefault
        val type: FlowMarkerType = FlowMarkerType.ARROW_CLOSED,
        val color: String? = null,
        val width: Double? = null,
        val height: Double? = null,
        val strokeWidth: Double? = null,
    ) : FlowMarker
}

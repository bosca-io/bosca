package bosca.design.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Retains VueFlow marker objects and SVG marker references across form/state round trips. */
object FlowMarkerSerializer : KSerializer<FlowMarker> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun serialize(encoder: Encoder, value: FlowMarker) = when (value) {
        is FlowMarker.Reference -> JsonElement.serializer().serialize(encoder, JsonPrimitive(value.id))
        is FlowMarker.Arrow -> FlowMarker.Arrow.serializer().serialize(encoder, value)
    }
    override fun deserialize(decoder: Decoder): FlowMarker = when (val value = JsonElement.serializer().deserialize(decoder)) {
        is JsonPrimitive -> { require(value.isString); FlowMarker.Reference(value.content) }
        else -> (decoder as JsonDecoder).json.decodeFromJsonElement(FlowMarker.Arrow.serializer(), value)
    }
}

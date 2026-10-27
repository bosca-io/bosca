package bosca.design.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Uses Chart.js point shapes for both BML state and browser serialization. */
object ChartValueSerializer : KSerializer<ChartValue> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun serialize(encoder: Encoder, value: ChartValue) {
        val element = when (value) {
            is ChartValue.Number -> number(value.value)
            is ChartValue.Scatter -> JsonObject(mapOf("x" to number(value.x), "y" to number(value.y)))
            is ChartValue.Bubble -> JsonObject(mapOf("x" to number(value.x), "y" to number(value.y), "r" to number(value.r)))
            ChartValue.Gap -> JsonNull
            is ChartValue.Custom -> value.value
        }
        JsonElement.serializer().serialize(encoder, element)
    }

    private fun number(value: Double): JsonPrimitive =
        if (value % 1.0 == 0.0 && value > Long.MIN_VALUE && value < Long.MAX_VALUE) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
    override fun deserialize(decoder: Decoder): ChartValue {
        val value = JsonElement.serializer().deserialize(decoder)
        if (value == JsonNull) return ChartValue.Gap
        if (value is JsonPrimitive && !value.isString) value.doubleOrNull?.let { return ChartValue.Number(it) }
        if (value is JsonObject) {
            val x = (value["x"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            val y = (value["y"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            val r = (value["r"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            if (x != null && y != null) {
                if (r != null && value.keys == setOf("x", "y", "r")) return ChartValue.Bubble(x, y, r)
                if (value.keys == setOf("x", "y")) return ChartValue.Scatter(x, y)
            }
        }
        return ChartValue.Custom(value)
    }
}

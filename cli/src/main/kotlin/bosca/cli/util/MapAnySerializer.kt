@file:Suppress("UNCHECKED_CAST")

package bosca.cli.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject

object MapAnySerializer : KSerializer<Map<String, Any>> {

    @Serializable
    private abstract class MapAnyMap : Map<String, Any>

    override val descriptor: SerialDescriptor = MapAnyMap.serializer().descriptor

    override fun deserialize(decoder: Decoder): Map<String, Any> {
        if (decoder is JsonDecoder) {
            val jsonObject = decoder.decodeJsonElement() as JsonObject
            return jsonObject.toAny() as Map<String, Any>
        } else {
            throw NotImplementedError("Decoder $decoder is not supported!")
        }
    }

    override fun serialize(encoder: Encoder, value: Map<String, Any>) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(value.toJsonElement())
        } else {
            throw NotImplementedError("Encoder $encoder is not supported!")
        }
    }
}

object ListMapAnySerializer : KSerializer<List<Map<String, Any>>> {

    @Serializable
    private abstract class MapAnyMap : Map<String, Any>

    override val descriptor: SerialDescriptor = MapAnyMap.serializer().descriptor

    override fun deserialize(decoder: Decoder): List<Map<String, Any>> {
        if (decoder is JsonDecoder) {
            val jsonObject = decoder.decodeJsonElement() as JsonObject
            return jsonObject.toAny() as List<Map<String, Any>>
        } else {
            throw NotImplementedError("Decoder $decoder is not supported!")
        }
    }

    override fun serialize(encoder: Encoder, value: List<Map<String, Any>>) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(value.toJsonElement())
        } else {
            throw NotImplementedError("Encoder $encoder is not supported!")
        }
    }
}
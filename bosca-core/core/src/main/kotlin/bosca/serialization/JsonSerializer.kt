package bosca.serialization

import bosca.serialization.JsonConverter.parseToJsonElement
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

class JsonElementSerializer : KSerializer<JsonElement> {

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("Json", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JsonElement) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): JsonElement = decoder.decodeString().parseToJsonElement()
}

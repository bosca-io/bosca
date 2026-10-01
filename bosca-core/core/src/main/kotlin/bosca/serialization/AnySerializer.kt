package bosca.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class AnySerializer : KSerializer<Any> {

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("Any", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Any) = TODO() // encoder.encodeString(value.asString())

    override fun deserialize(decoder: Decoder): Any = TODO()
//        Json.of(decoder.decodeString()) ?: error("Cannot deserialize null value")
}


class AnyNullableSerializer : KSerializer<Any?> {

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("Any", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Any?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeNotNullMark()
//            encoder.encodeString(value.asString())
            TODO()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): Any? {
        if (decoder.decodeNotNullMark()) {
//            return Json.of(decoder.decodeString())
            TODO()
        }
        return null
    }
}

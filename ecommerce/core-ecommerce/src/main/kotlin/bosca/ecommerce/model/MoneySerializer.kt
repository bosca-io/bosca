package bosca.ecommerce.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Encodes [Money] as a plain scale-4 decimal string (e.g. `"19.9900"`) so monetary values
 * round-trip losslessly through JSON / jsonb without binary-float drift. Used everywhere Money is
 * serialized: jsonb config blobs, cart items, and (mirrored by the GraphQL `Money` scalar) the API.
 *
 * Referenced explicitly via `@Serializable(with = MoneySerializer::class)` on [Money], so it never
 * needs reflective serializer resolution (GraalVM native-safe).
 */
object MoneySerializer : KSerializer<Money> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Money) {
        encoder.encodeString(value.amount.toPlainString())
    }

    override fun deserialize(decoder: Decoder): Money = Money.of(decoder.decodeString())
}

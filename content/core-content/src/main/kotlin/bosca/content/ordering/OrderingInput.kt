package bosca.content.ordering

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class OrderingInput(
    override val field: String? = null,
    @Serializable(with = AttributeLocationSerializer::class)
    override val location: AttributeLocation? = null,
    @Serializable(with = OrderSerializer::class)
    override val order: Order? = null,
    override val path: List<String>? = null,
    @Serializable(with = AttributeTypeLocationSerializer::class)
    override val type: AttributeType? = null,
) : IOrdering

class AttributeLocationSerializer : KSerializer<AttributeLocation> {

    override val descriptor = PrimitiveSerialDescriptor("AttributeLocation", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AttributeLocation) {
        encoder.encodeString(value.name.lowercase())
    }

    override fun deserialize(decoder: Decoder): AttributeLocation {
        return AttributeLocation.valueOf(decoder.decodeString().uppercase())
    }
}

class OrderSerializer : KSerializer<Order> {

    override val descriptor = PrimitiveSerialDescriptor("Order", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Order) {
        encoder.encodeString(value.name.lowercase())
    }

    override fun deserialize(decoder: Decoder): Order {
        return Order.valueOf(decoder.decodeString().uppercase())
    }
}

class AttributeTypeLocationSerializer : KSerializer<AttributeType> {

    override val descriptor = PrimitiveSerialDescriptor("AttributeType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AttributeType) {
        encoder.encodeString(value.name.lowercase())
    }

    override fun deserialize(decoder: Decoder): AttributeType {
        var value = decoder.decodeString().uppercase()
        if (value == "DATETIME") value = "DATE_TIME"
        return AttributeType.valueOf(value)
    }
}
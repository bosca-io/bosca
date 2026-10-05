package bosca.cli.api.scalars

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/**
 * Explicit (reflection-free, GraalVM-native-safe) kotlinx.serialization serializers for the GraphQL custom
 * scalars the Bosca-native client maps onto JDK/Kotlin types — wired via the codegen's `--scalar` mappings and
 * applied through a generated `@file:UseSerializers(...)` header. The wire formats match the previous Apollo
 * scalar adapters ([bosca.cli.api.NetworkClient]) so migrating call sites is type-for-type.
 */

/** `UUID` scalar ⇄ [Uuid], serialized as its canonical string form. */
object UuidSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("bosca.UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Uuid) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Uuid = Uuid.parse(decoder.decodeString())
}

/** `DateTime` scalar ⇄ [ZonedDateTime], serialized as ISO-8601 offset date-time (matching the old Apollo adapter). */
object ZonedDateTimeSerializer : KSerializer<ZonedDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("bosca.DateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ZonedDateTime) =
        encoder.encodeString(value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    override fun deserialize(decoder: Decoder): ZonedDateTime = ZonedDateTime.parse(decoder.decodeString())
}

package bosca.core.platform.graphql

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * kotlinx.serialization scalar serializers for the generated bosca-graphql-client operations — the
 * native-safe counterpart of Apollo `Adapter<T>` classes such as `UuidAdapter` / `DateTimeAdapter`. Both
 * scalars travel as ISO strings on the wire, matching the GraphQL `UUID` / `DateTime` scalar format. Wired
 * into codegen via the `boscaGraphql { scalarMappings }` block in build.gradle.kts.
 */
@OptIn(ExperimentalUuidApi::class)
object UuidSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Uuid) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Uuid = Uuid.parse(decoder.decodeString())
}

@OptIn(ExperimentalTime::class)
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

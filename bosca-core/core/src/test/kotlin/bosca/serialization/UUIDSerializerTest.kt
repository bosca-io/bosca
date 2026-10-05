package bosca.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class UUIDSerializerTest {

    @Serializable
    data class UUIDHolder(
        @Serializable(with = UUIDSerializer::class)
        val id: UUID
    )

    private val json = Json { encodeDefaults = true }

    @Test
    fun descriptorNameIsUUID() {
        val serializer = UUIDSerializer()
        assertEquals("UUID", serializer.descriptor.serialName)
    }

    @Test
    fun serializeAndDeserializeRoundTrip() {
        val uuid = UUID.parse("550e8400-e29b-41d4-a716-446655440000")
        val holder = UUIDHolder(id = uuid)
        val encoded = json.encodeToString(UUIDHolder.serializer(), holder)
        val decoded = json.decodeFromString(UUIDHolder.serializer(), encoded)
        assertEquals(holder, decoded)
        assertEquals(uuid, decoded.id)
    }

    @Test
    fun serializesToStringRepresentation() {
        val uuid = UUID.parse("123e4567-e89b-12d3-a456-426614174000")
        val holder = UUIDHolder(id = uuid)
        val encoded = json.encodeToString(UUIDHolder.serializer(), holder)
        assert(encoded.contains("123e4567-e89b-12d3-a456-426614174000"))
    }
}

package bosca.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class OffsetDateTimeSerializerTest {

    @Serializable
    data class OffsetHolder(
        @Serializable(with = OffsetDateTimeSerializer::class)
        val date: java.time.OffsetDateTime
    )

    private val json = Json { encodeDefaults = true }

    @Test
    fun descriptorName() {
        val serializer = OffsetDateTimeSerializer()
        assertEquals("OffsetDateTime", serializer.descriptor.serialName)
    }

    @Test
    fun roundTrip() {
        val odt = java.time.OffsetDateTime.parse("2024-06-15T10:30:00Z")
        val holder = OffsetHolder(date = odt)
        val encoded = json.encodeToString(OffsetHolder.serializer(), holder)
        val decoded = json.decodeFromString(OffsetHolder.serializer(), encoded)
        assertEquals(odt, decoded.date)
    }

    @Test
    fun serializesToStringRepresentation() {
        val odt = java.time.OffsetDateTime.parse("2024-06-15T10:30:00Z")
        val holder = OffsetHolder(date = odt)
        val encoded = json.encodeToString(OffsetHolder.serializer(), holder)
        assert(encoded.contains("2024-06-15T10:30"))
    }
}

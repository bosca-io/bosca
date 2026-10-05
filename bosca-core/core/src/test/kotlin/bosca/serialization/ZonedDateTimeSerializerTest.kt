package bosca.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ZonedDateTimeSerializerTest {

    @Serializable
    data class ZonedHolder(
        @Serializable(with = ZonedDateTimeSerializer::class)
        val date: java.time.ZonedDateTime
    )

    private val json = Json { encodeDefaults = true }

    @Test
    fun descriptorName() {
        val serializer = ZonedDateTimeSerializer()
        assertEquals("ZonedDateTime", serializer.descriptor.serialName)
    }

    @Test
    fun roundTrip() {
        val zdt = Instant.ofEpochMilli(1718448000000L).atZone(ZoneId.of("UTC"))
        val holder = ZonedHolder(date = zdt)
        val encoded = json.encodeToString(ZonedHolder.serializer(), holder)
        val decoded = json.decodeFromString(ZonedHolder.serializer(), encoded)
        assertEquals(zdt, decoded.date)
    }

    @Test
    fun serializesToEpochMillis() {
        val zdt = Instant.ofEpochMilli(1718448000000L).atZone(ZoneId.of("UTC"))
        val holder = ZonedHolder(date = zdt)
        val encoded = json.encodeToString(ZonedHolder.serializer(), holder)
        assert(encoded.contains("1718448000000"))
    }
}

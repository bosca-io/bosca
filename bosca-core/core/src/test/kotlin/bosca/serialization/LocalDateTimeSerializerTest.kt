package bosca.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDateTimeSerializerTest {

    @Serializable
    data class DateHolder(
        @Serializable(with = LocalDateTimeSerializer::class)
        val date: java.time.LocalDateTime
    )

    private val json = Json { encodeDefaults = true }

    @Test
    fun descriptorName() {
        val serializer = LocalDateTimeSerializer()
        assertEquals("LocalDateTime", serializer.descriptor.serialName)
    }

    @Test
    fun roundTrip() {
        val dt = java.time.LocalDateTime.of(2024, 6, 15, 10, 30, 0)
        val holder = DateHolder(date = dt)
        val encoded = json.encodeToString(DateHolder.serializer(), holder)
        val decoded = json.decodeFromString(DateHolder.serializer(), encoded)
        assertEquals(dt, decoded.date)
    }

    @Test
    fun serializesToEpochMillis() {
        val dt = java.time.LocalDateTime.of(2024, 1, 1, 0, 0, 0)
        val expectedMillis = dt.toInstant(ZoneOffset.UTC).toEpochMilli()
        val holder = DateHolder(date = dt)
        val encoded = json.encodeToString(DateHolder.serializer(), holder)
        assert(encoded.contains(expectedMillis.toString()))
    }
}

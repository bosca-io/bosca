package bosca.content.timeevent.events

import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Coverage for [TimeEventChanged] and [TIME_EVENT_CHANGED_CHANNEL].
 *
 * The data class carries a `@Contextual` [UUID] `metadataId`, so serialization
 * requires a [Json] whose serializers module contextually registers
 * [UUIDSerializer] — mirroring how production job executors and mutations publish
 * this notification.
 */
class TimeEventChangedCoverageTest {

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
        }
    }

    @Test
    fun `constructor preserves all fields`() {
        val id = UUID.random()
        val event = TimeEventChanged(
            metadataId = id,
            metadataVersion = 3,
            eventCount = 12,
        )
        assertEquals(id, event.metadataId)
        assertEquals(3, event.metadataVersion)
        assertEquals(12, event.eventCount)
    }

    @Test
    fun `serialize and deserialize round trip`() {
        val event = TimeEventChanged(
            metadataId = UUID.parse("550e8400-e29b-41d4-a716-446655440000"),
            metadataVersion = 7,
            eventCount = 42,
        )
        val encoded = json.encodeToString(TimeEventChanged.serializer(), event)
        val decoded = json.decodeFromString(TimeEventChanged.serializer(), encoded)
        assertEquals(event, decoded)
        assertEquals(event.metadataId, decoded.metadataId)
        assertEquals(event.metadataVersion, decoded.metadataVersion)
        assertEquals(event.eventCount, decoded.eventCount)
    }

    @Test
    fun `serialization emits the metadata id as its string form`() {
        val event = TimeEventChanged(
            metadataId = UUID.parse("123e4567-e89b-12d3-a456-426614174000"),
            metadataVersion = 1,
            eventCount = 0,
        )
        val encoded = json.encodeToString(TimeEventChanged.serializer(), event)
        assertTrue(encoded.contains("123e4567-e89b-12d3-a456-426614174000"))
    }

    @Test
    fun `zero values round trip`() {
        val event = TimeEventChanged(
            metadataId = UUID.random(),
            metadataVersion = 0,
            eventCount = 0,
        )
        val decoded = json.decodeFromString(
            TimeEventChanged.serializer(),
            json.encodeToString(TimeEventChanged.serializer(), event),
        )
        assertEquals(event, decoded)
    }

    @Test
    fun `copy overrides selected fields`() {
        val id = UUID.random()
        val original = TimeEventChanged(metadataId = id, metadataVersion = 2, eventCount = 5)
        val updated = original.copy(eventCount = 9)
        assertEquals(id, updated.metadataId)
        assertEquals(2, updated.metadataVersion)
        assertEquals(9, updated.eventCount)
        assertEquals(original, original.copy())
    }

    @Test
    fun `equals and hashCode reflect value equality`() {
        val id = UUID.random()
        val a = TimeEventChanged(metadataId = id, metadataVersion = 1, eventCount = 3)
        val b = TimeEventChanged(metadataId = id, metadataVersion = 1, eventCount = 3)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `differing event counts are not equal`() {
        val id = UUID.random()
        val a = TimeEventChanged(metadataId = id, metadataVersion = 1, eventCount = 3)
        val c = TimeEventChanged(metadataId = id, metadataVersion = 1, eventCount = 4)
        assertNotEquals(a, c)
    }

    @Test
    fun `differing versions are not equal`() {
        val id = UUID.random()
        val a = TimeEventChanged(metadataId = id, metadataVersion = 1, eventCount = 3)
        val c = TimeEventChanged(metadataId = id, metadataVersion = 2, eventCount = 3)
        assertNotEquals(a, c)
    }

    @Test
    fun `toString includes field values`() {
        val event = TimeEventChanged(metadataId = UUID.random(), metadataVersion = 8, eventCount = 84)
        val text = event.toString()
        assertTrue(text.contains("8"))
        assertTrue(text.contains("84"))
    }

    @Test
    fun `channel constant has expected value`() {
        assertEquals("bosca.content.timeevent.changed", TIME_EVENT_CHANGED_CHANNEL)
    }
}

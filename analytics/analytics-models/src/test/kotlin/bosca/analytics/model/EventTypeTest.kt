package bosca.analytics.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class EventTypeTest {

    private val json = Json

    @Test
    fun `Session serializes to session`() {
        val serialized = json.encodeToString(EventType.Session)
        assertEquals("\"session\"", serialized)
    }

    @Test
    fun `Interaction serializes to interaction`() {
        val serialized = json.encodeToString(EventType.Interaction)
        assertEquals("\"interaction\"", serialized)
    }

    @Test
    fun `Impression serializes to impression`() {
        val serialized = json.encodeToString(EventType.Impression)
        assertEquals("\"impression\"", serialized)
    }

    @Test
    fun `Completion serializes to completion`() {
        val serialized = json.encodeToString(EventType.Completion)
        assertEquals("\"completion\"", serialized)
    }

    @Test
    fun `Installation serializes to installation`() {
        val serialized = json.encodeToString(EventType.Installation)
        assertEquals("\"installation\"", serialized)
    }

    @Test
    fun `Assignment serializes to assignment`() {
        val serialized = json.encodeToString(EventType.Assignment)
        assertEquals("\"assignment\"", serialized)
    }

    @Test
    fun `Error serializes to error`() {
        val serialized = json.encodeToString(EventType.Error)
        assertEquals("\"error\"", serialized)
    }

    @Test
    fun `deserialize session to Session`() {
        val deserialized = json.decodeFromString<EventType>("\"session\"")
        assertEquals(EventType.Session, deserialized)
    }

    @Test
    fun `deserialize error to Error`() {
        val deserialized = json.decodeFromString<EventType>("\"error\"")
        assertEquals(EventType.Error, deserialized)
    }

    @Test
    fun `all event types round-trip correctly`() {
        for (eventType in EventType.entries) {
            val serialized = json.encodeToString(eventType)
            val deserialized = json.decodeFromString<EventType>(serialized)
            assertEquals(eventType, deserialized)
        }
    }

    @Test
    fun `EventType has exactly eight variants`() {
        assertEquals(8, EventType.entries.size)
    }

    @Test
    fun `existing protobuf ordinals remain stable`() {
        assertEquals(0, EventType.Session.ordinal)
        assertEquals(1, EventType.Interaction.ordinal)
        assertEquals(2, EventType.Impression.ordinal)
        assertEquals(3, EventType.Completion.ordinal)
        assertEquals(4, EventType.Installation.ordinal)
        assertEquals(5, EventType.Error.ordinal)
        assertEquals(6, EventType.Heartbeat.ordinal)
        assertEquals(7, EventType.Assignment.ordinal)
    }
}

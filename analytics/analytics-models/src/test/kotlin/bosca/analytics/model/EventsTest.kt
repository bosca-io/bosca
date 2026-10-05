package bosca.analytics.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventsTest {

    private val device = Device(
        installationId = "install-1",
        manufacturer = "Apple",
        model = "iPhone 15",
        platform = "iOS",
        primaryLocale = "en-US",
        systemName = "iOS",
        timezone = "UTC",
        type = "phone",
        version = "17.0"
    )

    @Test
    fun `Events stores required fields`() {
        val event = Event(created = 1000, type = EventType.Interaction)
        val events = Events(
            events = listOf(event),
            sent = 1000,
            sentMicros = 1000000
        )
        assertEquals(1, events.events.size)
        assertEquals(1000, events.sent)
        assertEquals(1000000, events.sentMicros)
    }

    @Test
    fun `Events has null defaults for optional fields`() {
        val events = Events(
            events = emptyList(),
            sent = 0,
            sentMicros = 0
        )
        assertNull(events.context)
        assertNull(events.received)
        assertNull(events.receivedMicros)
    }

    @Test
    fun `Events with context and received timestamps`() {
        val context = EventContext(
            appId = "app",
            appVersion = "1.0",
            device = device,
            sessionId = "s1"
        )
        val events = Events(
            context = context,
            events = listOf(Event(created = 100, type = EventType.Session)),
            sent = 100,
            sentMicros = 100000,
            received = 200,
            receivedMicros = 200000
        )
        assertEquals("app", events.context?.appId)
        assertEquals(200, events.received)
        assertEquals(200000, events.receivedMicros)
    }

    @Test
    fun `Events equality`() {
        val event = Event(created = 1, type = EventType.Impression)
        val a = Events(events = listOf(event), sent = 1, sentMicros = 1000)
        val b = Events(events = listOf(event), sent = 1, sentMicros = 1000)
        assertEquals(a, b)
    }

    @Test
    fun `assignment event uses the lowercase analytics wire value`() {
        val encoded = Json.encodeToString(
            Event.serializer(),
            Event(created = 1, type = EventType.Assignment),
        )

        assertEquals(true, encoded.contains("\"type\":\"assignment\""))
    }
}

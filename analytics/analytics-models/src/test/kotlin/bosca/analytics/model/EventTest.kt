package bosca.analytics.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serialize Event with error field`() {
        val event = Event(
            created = 1700000000000L,
            type = EventType.Error,
            error = ErrorInfo(
                message = "Uncaught TypeError",
                type = "TypeError",
                stackTrace = "at foo()",
                fatal = true,
                code = "ERR_TYPE"
            )
        )
        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<Event>(serialized)

        assertEquals(EventType.Error, deserialized.type)
        assertNotNull(deserialized.error)
        assertEquals("Uncaught TypeError", deserialized.error!!.message)
        assertEquals("TypeError", deserialized.error!!.type)
        assertTrue(deserialized.error!!.fatal)
    }

    @Test
    fun `serialize Event without error field`() {
        val event = Event(
            created = 1700000000000L,
            type = EventType.Impression,
            element = Element(id = "btn-1", type = "button")
        )
        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<Event>(serialized)

        assertEquals(EventType.Impression, deserialized.type)
        assertNull(deserialized.error)
        assertNotNull(deserialized.element)
    }

    @Test
    fun `error field defaults to null`() {
        val event = Event(created = 1700000000000L, type = EventType.Session)
        assertNull(event.error)
    }

    @Test
    fun `deserialize Event with error from JSON`() {
        val jsonStr = """{
            "created": 1700000000000,
            "type": "error",
            "error": {
                "message": "fetch failed",
                "type": "NetworkError",
                "stack_trace": "at fetch()",
                "fatal": false,
                "code": "NET_ERR"
            }
        }"""
        val event = json.decodeFromString<Event>(jsonStr)

        assertEquals(EventType.Error, event.type)
        assertNotNull(event.error)
        assertEquals("fetch failed", event.error!!.message)
        assertEquals("NetworkError", event.error!!.type)
        assertEquals("at fetch()", event.error!!.stackTrace)
        assertFalse(event.error!!.fatal)
        assertEquals("NET_ERR", event.error!!.code)
    }

    @Test
    fun `deserialize Event without error from JSON`() {
        val jsonStr = """{"created": 1700000000000, "type": "impression"}"""
        val event = json.decodeFromString<Event>(jsonStr)

        assertEquals(EventType.Impression, event.type)
        assertNull(event.error)
    }

    @Test
    fun `Event with all fields round-trips correctly`() {
        val event = Event(
            created = 1700000000000L,
            createdMicros = 1700000000000000L,
            type = EventType.Error,
            element = Element(id = "el-1", type = "div", extras = JsonNull),
            clientId = "client-abc",
            error = ErrorInfo(
                message = "crash",
                type = "Fatal",
                stackTrace = "at main()",
                fatal = true,
                code = "CRASH_001"
            )
        )
        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<Event>(serialized)

        assertEquals(event.created, deserialized.created)
        assertEquals(event.createdMicros, deserialized.createdMicros)
        assertEquals(event.type, deserialized.type)
        assertEquals(event.clientId, deserialized.clientId)
        assertEquals(event.error, deserialized.error)
    }

    @Test
    fun `clientId serializes as client_id in JSON`() {
        val event = Event(
            created = 1700000000000L,
            type = EventType.Session,
            clientId = "my-client"
        )
        val serialized = json.encodeToString(event)

        assertTrue(serialized.contains("\"client_id\""))
        assertFalse(serialized.contains("\"clientId\""))
    }

    @Test
    fun `createdMicros serializes as created_micros in JSON`() {
        val event = Event(
            created = 1700000000000L,
            createdMicros = 123456789L,
            type = EventType.Interaction
        )
        val serialized = json.encodeToString(event)

        assertTrue(serialized.contains("\"created_micros\""))
    }

    @Test
    fun `error event with minimal error info`() {
        val event = Event(
            created = 1700000000000L,
            type = EventType.Error,
            error = ErrorInfo(message = "oops")
        )
        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<Event>(serialized)

        assertEquals("oops", deserialized.error?.message)
        assertNull(deserialized.error?.type)
        assertNull(deserialized.error?.stackTrace)
        assertFalse(deserialized.error!!.fatal)
        assertNull(deserialized.error?.code)
    }
}

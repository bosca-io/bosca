package bosca.content.timeevent.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TimeEventTypeTest {

    @Test
    fun `stores all required fields`() {
        val eventType = TimeEventType(
            id = "highlight",
            name = "Highlight",
            description = "A highlighted time range"
        )
        assertEquals("highlight", eventType.id)
        assertEquals("Highlight", eventType.name)
        assertEquals("A highlighted time range", eventType.description)
    }

    @Test
    fun `optional fields default to null`() {
        val eventType = TimeEventType(
            id = "marker",
            name = "Marker",
            description = "A marker event"
        )
        assertNull(eventType.schema)
        assertNull(eventType.configuration)
    }

    @Test
    fun `stores schema and configuration when provided`() {
        val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val config = JsonObject(mapOf("color" to JsonPrimitive("red")))
        val eventType = TimeEventType(
            id = "note",
            name = "Note",
            description = "A note event",
            schema = schema,
            configuration = config
        )
        assertEquals(schema, eventType.schema)
        assertEquals(config, eventType.configuration)
    }

    @Test
    fun `data class equality`() {
        val a = TimeEventType(id = "t", name = "T", description = "d")
        val b = TimeEventType(id = "t", name = "T", description = "d")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality on different id`() {
        val a = TimeEventType(id = "t1", name = "T", description = "d")
        val b = TimeEventType(id = "t2", name = "T", description = "d")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies fields`() {
        val original = TimeEventType(id = "t", name = "Original", description = "d")
        val copied = original.copy(name = "Updated")
        assertEquals("Updated", copied.name)
        assertEquals("t", copied.id)
    }
}

package bosca.content.timeevent.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TimeEventTypeInputTest {

    @Test
    fun `TimeEventTypeInput stores required fields`() {
        val input = TimeEventTypeInput(
            id = "type-1",
            name = "Highlight",
            description = "A highlight event type"
        )
        assertEquals("type-1", input.id)
        assertEquals("Highlight", input.name)
        assertEquals("A highlight event type", input.description)
    }

    @Test
    fun `TimeEventTypeInput optional fields default to null`() {
        val input = TimeEventTypeInput(
            id = "t", name = "n", description = "d"
        )
        assertNull(input.schema)
        assertNull(input.configuration)
    }

    @Test
    fun `TimeEventTypeInput stores all properties`() {
        val schema = buildJsonObject { put("type", "object") }
        val config = buildJsonObject { put("color", "red") }

        val input = TimeEventTypeInput(
            id = "type-2",
            name = "Annotation",
            description = "An annotation event type",
            schema = schema,
            configuration = config
        )

        assertEquals("type-2", input.id)
        assertEquals("Annotation", input.name)
        assertEquals("An annotation event type", input.description)
        assertEquals(schema, input.schema)
        assertEquals(config, input.configuration)
    }

    @Test
    fun `TimeEventTypeInput data class equality`() {
        val input1 = TimeEventTypeInput(id = "t", name = "n", description = "d")
        val input2 = TimeEventTypeInput(id = "t", name = "n", description = "d")
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `TimeEventTypeInput inequality on different id`() {
        val input1 = TimeEventTypeInput(id = "t1", name = "n", description = "d")
        val input2 = TimeEventTypeInput(id = "t2", name = "n", description = "d")
        assertNotEquals(input1, input2)
    }

    @Test
    fun `TimeEventTypeInput copy preserves unchanged fields`() {
        val config = buildJsonObject { put("k", "v") }
        val input = TimeEventTypeInput(
            id = "t1", name = "original", description = "desc",
            configuration = config
        )
        val copied = input.copy(name = "updated")
        assertEquals("updated", copied.name)
        assertEquals("t1", copied.id)
        assertEquals("desc", copied.description)
        assertEquals(config, copied.configuration)
    }
}

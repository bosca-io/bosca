package bosca.content.timeevent.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TimeEventInputTest {

    @Test
    fun `TimeEventInput stores required fields`() {
        val input = TimeEventInput(
            type = "highlight",
            startOffsetMs = 1500L
        )
        assertEquals("highlight", input.type)
        assertEquals(1500L, input.startOffsetMs)
    }

    @Test
    fun `TimeEventInput optional fields default to null`() {
        val input = TimeEventInput(type = "marker", startOffsetMs = 0L)
        assertNull(input.endOffsetMs)
        assertNull(input.sort)
        assertNull(input.attributes)
    }

    @Test
    fun `TimeEventInput stores all properties`() {
        val attrs = buildJsonObject { put("color", "blue") }

        val input = TimeEventInput(
            type = "annotation",
            startOffsetMs = 1000L,
            endOffsetMs = 5000L,
            sort = 3,
            attributes = attrs
        )

        assertEquals("annotation", input.type)
        assertEquals(1000L, input.startOffsetMs)
        assertEquals(5000L, input.endOffsetMs)
        assertEquals(3, input.sort)
        assertEquals(attrs, input.attributes)
    }

    @Test
    fun `TimeEventInput data class equality`() {
        val input1 = TimeEventInput(type = "t", startOffsetMs = 100L)
        val input2 = TimeEventInput(type = "t", startOffsetMs = 100L)
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `TimeEventInput copy preserves unchanged fields`() {
        val input = TimeEventInput(
            type = "highlight",
            startOffsetMs = 500L,
            endOffsetMs = 1000L
        )
        val copied = input.copy(endOffsetMs = 2000L)
        assertEquals("highlight", copied.type)
        assertEquals(500L, copied.startOffsetMs)
        assertEquals(2000L, copied.endOffsetMs)
    }
}

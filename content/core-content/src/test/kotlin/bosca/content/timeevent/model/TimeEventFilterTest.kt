package bosca.content.timeevent.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TimeEventFilterTest {

    @Test
    fun `all fields default to null`() {
        val filter = TimeEventFilter()
        assertNull(filter.types)
        assertNull(filter.startAfterMs)
        assertNull(filter.endBeforeMs)
        assertNull(filter.atOffsetMs)
    }

    @Test
    fun `stores types list`() {
        val filter = TimeEventFilter(types = listOf("highlight", "bookmark"))
        assertEquals(listOf("highlight", "bookmark"), filter.types)
    }

    @Test
    fun `stores time range fields`() {
        val filter = TimeEventFilter(
            startAfterMs = 1000L,
            endBeforeMs = 5000L,
            atOffsetMs = 2500L
        )
        assertEquals(1000L, filter.startAfterMs)
        assertEquals(5000L, filter.endBeforeMs)
        assertEquals(2500L, filter.atOffsetMs)
    }

    @Test
    fun `stores all fields together`() {
        val filter = TimeEventFilter(
            types = listOf("marker"),
            startAfterMs = 0L,
            endBeforeMs = 10000L,
            atOffsetMs = 5000L
        )
        assertEquals(listOf("marker"), filter.types)
        assertEquals(0L, filter.startAfterMs)
        assertEquals(10000L, filter.endBeforeMs)
        assertEquals(5000L, filter.atOffsetMs)
    }

    @Test
    fun `data class equality`() {
        val a = TimeEventFilter(types = listOf("a"), startAfterMs = 100L)
        val b = TimeEventFilter(types = listOf("a"), startAfterMs = 100L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality`() {
        val a = TimeEventFilter(startAfterMs = 100L)
        val b = TimeEventFilter(startAfterMs = 200L)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies fields`() {
        val original = TimeEventFilter(types = listOf("highlight"))
        val copied = original.copy(startAfterMs = 500L)
        assertEquals(listOf("highlight"), copied.types)
        assertEquals(500L, copied.startAfterMs)
    }
}

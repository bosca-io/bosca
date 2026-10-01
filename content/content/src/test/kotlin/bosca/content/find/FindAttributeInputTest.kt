package bosca.content.find

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindAttributeInputTest {

    @Test
    fun `field preservation with all values`() {
        val input = FindAttributeInput(key = "topic", value = "science")
        assertEquals("topic", input.key)
        assertEquals("science", input.value)
    }

    @Test
    fun `value defaults to null`() {
        val input = FindAttributeInput(key = "category")
        assertEquals("category", input.key)
        assertNull(input.value)
    }

    @Test
    fun `data class equality`() {
        val a = FindAttributeInput(key = "tag", value = "featured")
        val b = FindAttributeInput(key = "tag", value = "featured")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes value`() {
        val input = FindAttributeInput(key = "tag", value = "old")
        val modified = input.copy(value = "new")
        assertEquals("tag", modified.key)
        assertEquals("new", modified.value)
    }

    @Test
    fun `key with empty string`() {
        val input = FindAttributeInput(key = "")
        assertEquals("", input.key)
        assertNull(input.value)
    }
}

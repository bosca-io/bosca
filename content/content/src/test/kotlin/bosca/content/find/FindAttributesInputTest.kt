package bosca.content.find

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindAttributesInputTest {

    @Test
    fun `field preservation with list of attributes`() {
        val attrs = listOf(
            FindAttributeInput(key = "topic", value = "science"),
            FindAttributeInput(key = "level", value = "advanced")
        )
        val input = FindAttributesInput(attributes = attrs)
        assertEquals(2, input.attributes.size)
        assertEquals("topic", input.attributes[0].key)
        assertEquals("science", input.attributes[0].value)
        assertEquals("level", input.attributes[1].key)
        assertEquals("advanced", input.attributes[1].value)
    }

    @Test
    fun `empty attributes list`() {
        val input = FindAttributesInput(attributes = emptyList())
        assertTrue(input.attributes.isEmpty())
    }

    @Test
    fun `data class equality`() {
        val attrs = listOf(FindAttributeInput(key = "k", value = "v"))
        val a = FindAttributesInput(attributes = attrs)
        val b = FindAttributesInput(attributes = attrs)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes attributes`() {
        val original = FindAttributesInput(attributes = listOf(FindAttributeInput(key = "a")))
        val newAttrs = listOf(FindAttributeInput(key = "b", value = "val"))
        val modified = original.copy(attributes = newAttrs)
        assertEquals(1, modified.attributes.size)
        assertEquals("b", modified.attributes[0].key)
    }
}

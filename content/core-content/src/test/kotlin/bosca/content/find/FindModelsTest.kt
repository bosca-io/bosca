package bosca.content.find

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindModelsTest {

    // --- FindAttributeInput ---

    @Test
    fun `FindAttributeInput stores key and optional value`() {
        val input = FindAttributeInput(key = "status")
        assertEquals("status", input.key)
        assertNull(input.value)
    }

    @Test
    fun `FindAttributeInput stores key and value`() {
        val input = FindAttributeInput(key = "type", value = "article")
        assertEquals("type", input.key)
        assertEquals("article", input.value)
    }

    // --- FindAttributesInput ---

    @Test
    fun `FindAttributesInput stores list of attributes`() {
        val input = FindAttributesInput(
            attributes = listOf(
                FindAttributeInput(key = "a", value = "1"),
                FindAttributeInput(key = "b")
            )
        )
        assertEquals(2, input.attributes.size)
    }

    // --- FindQueryInput ---

    @Test
    fun `FindQueryInput defaults to null`() {
        val input = FindQueryInput()
        assertNull(input.attributes)
        assertNull(input.categoryIds)
        assertNull(input.collectionType)
        assertNull(input.contentTypes)
        assertNull(input.languageTags)
        assertNull(input.extensionFilter)
        assertNull(input.offset)
        assertNull(input.limit)
        assertNull(input.ordering)
        assertNull(input.traitIds)
    }

    @Test
    fun `FindQueryInput stores pagination parameters`() {
        val input = FindQueryInput(offset = 10, limit = 25)
        assertEquals(10, input.offset)
        assertEquals(25, input.limit)
    }
}

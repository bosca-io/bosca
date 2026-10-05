package bosca.content.attributes.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttributesFilterInputTest {

    @Test
    fun `AttributesFilterInput defaults`() {
        val input = AttributesFilterInput()
        assertEquals(emptyList(), input.attributes)
        assertNull(input.childAttributes)
    }

    @Test
    fun `AttributesFilterInput stores attributes list`() {
        val input = AttributesFilterInput(
            attributes = listOf("name", "description", "type")
        )
        assertEquals(listOf("name", "description", "type"), input.attributes)
        assertNull(input.childAttributes)
    }

    @Test
    fun `AttributesFilterInput stores nested childAttributes`() {
        val child = AttributesFilterInput(attributes = listOf("nested"))
        val input = AttributesFilterInput(
            attributes = listOf("parent"),
            childAttributes = child
        )
        assertEquals(listOf("parent"), input.attributes)
        assertEquals(listOf("nested"), input.childAttributes!!.attributes)
    }

    @Test
    fun `AttributesFilterInput filter on map extracts matching keys`() {
        val input = AttributesFilterInput(attributes = listOf("name", "type"))
        val data = mapOf("name" to "Test", "type" to "article", "extra" to "ignored")
        val result = input.filter(data)
        @Suppress("UNCHECKED_CAST")
        val resultMap = result as Map<String, Any>
        assertEquals(2, resultMap.size)
        assertEquals("Test", resultMap["name"])
        assertEquals("article", resultMap["type"])
    }

    @Test
    fun `AttributesFilterInput filter on map with missing keys`() {
        val input = AttributesFilterInput(attributes = listOf("name", "missing"))
        val data = mapOf("name" to "Test")
        val result = input.filter(data)
        @Suppress("UNCHECKED_CAST")
        val resultMap = result as Map<String, Any>
        assertEquals(1, resultMap.size)
        assertEquals("Test", resultMap["name"])
    }

    @Test
    fun `AttributesFilterInput filter on list applies childAttributes`() {
        val child = AttributesFilterInput(attributes = listOf("name"))
        val input = AttributesFilterInput(
            attributes = listOf("name"),
            childAttributes = child
        )
        val data = listOf(
            mapOf("name" to "Item1", "extra" to "x"),
            mapOf("name" to "Item2", "extra" to "y")
        )
        val result = input.filter(data)
        @Suppress("UNCHECKED_CAST")
        val resultList = result as List<Map<String, Any>>
        assertEquals(2, resultList.size)
        assertEquals(mapOf("name" to "Item1"), resultList[0])
        assertEquals(mapOf("name" to "Item2"), resultList[1])
    }

    @Test
    fun `AttributesFilterInput filter on non-collection returns value as-is`() {
        val input = AttributesFilterInput(attributes = listOf("x"))
        val result = input.filter("plain string")
        assertEquals("plain string", result)
    }

    @Test
    fun `AttributesFilterInput data class equality`() {
        val input1 = AttributesFilterInput(attributes = listOf("a", "b"))
        val input2 = AttributesFilterInput(attributes = listOf("a", "b"))
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `AttributesFilterInput copy preserves unchanged fields`() {
        val child = AttributesFilterInput(attributes = listOf("nested"))
        val input = AttributesFilterInput(attributes = listOf("a"), childAttributes = child)
        val copied = input.copy(attributes = listOf("b"))
        assertEquals(listOf("b"), copied.attributes)
        assertEquals(child, copied.childAttributes)
    }
}

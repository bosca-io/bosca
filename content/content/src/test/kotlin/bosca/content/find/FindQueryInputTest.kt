package bosca.content.find

import bosca.content.collection.model.CollectionType
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FindQueryInputTest {

    @Test
    fun `FindAttributeInput creation with key and value`() {
        val input = FindAttributeInput(key = "color", value = "red")
        assertEquals("color", input.key)
        assertEquals("red", input.value)
    }

    @Test
    fun `FindAttributeInput value defaults to null`() {
        val input = FindAttributeInput(key = "tag")
        assertEquals("tag", input.key)
        assertNull(input.value)
    }

    @Test
    fun `FindAttributeInput data class equality`() {
        val a = FindAttributeInput(key = "k", value = "v")
        val b = FindAttributeInput(key = "k", value = "v")
        assertEquals(a, b)
    }

    @Test
    fun `FindAttributeInput copy changes value`() {
        val original = FindAttributeInput(key = "k", value = "v1")
        val copy = original.copy(value = "v2")
        assertEquals("v2", copy.value)
        assertEquals("k", copy.key)
    }

    @Test
    fun `FindAttributesInput wraps list of FindAttributeInput`() {
        val attrs = listOf(
            FindAttributeInput(key = "a", value = "1"),
            FindAttributeInput(key = "b", value = "2")
        )
        val input = FindAttributesInput(attributes = attrs)
        assertEquals(2, input.attributes.size)
        assertEquals("a", input.attributes[0].key)
        assertEquals("2", input.attributes[1].value)
    }

    @Test
    fun `FindAttributesInput with empty list`() {
        val input = FindAttributesInput(attributes = emptyList())
        assertTrue(input.attributes.isEmpty())
    }

    @Test
    fun `FindQueryInput all fields default to null`() {
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
    fun `FindQueryInput with all fields populated`() {
        val catId = UUID.random()
        val input = FindQueryInput(
            attributes = listOf(FindAttributesInput(listOf(FindAttributeInput("k", "v")))),
            categoryIds = listOf(catId),
            collectionType = CollectionType.FOLDER,
            contentTypes = listOf("text/plain"),
            languageTags = listOf("en", "fr"),
            extensionFilter = ExtensionFilterType.DOCUMENT,
            offset = 10L,
            limit = 25,
            traitIds = listOf("trait1")
        )
        assertEquals(1, input.attributes?.size)
        assertEquals(catId, input.categoryIds?.first())
        assertEquals(CollectionType.FOLDER, input.collectionType)
        assertEquals(listOf("text/plain"), input.contentTypes)
        assertEquals(listOf("en", "fr"), input.languageTags)
        assertEquals(ExtensionFilterType.DOCUMENT, input.extensionFilter)
        assertEquals(10L, input.offset)
        assertEquals(25, input.limit)
        assertEquals(listOf("trait1"), input.traitIds)
    }

    @Test
    fun `FindQueryInput data class equality with defaults`() {
        val a = FindQueryInput()
        val b = FindQueryInput()
        assertEquals(a, b)
    }

    @Test
    fun `FindQueryInput copy updates single field`() {
        val original = FindQueryInput(limit = 10)
        val copy = original.copy(offset = 5L)
        assertEquals(5L, copy.offset)
        assertEquals(10, copy.limit)
    }
}

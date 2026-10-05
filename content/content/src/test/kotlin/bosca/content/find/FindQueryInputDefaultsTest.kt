package bosca.content.find

import bosca.content.collection.model.CollectionType
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindQueryInputDefaultsTest {

    @Test
    fun `all fields default to null`() {
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
    fun `field preservation with all values`() {
        val catId = UUID.random()
        val attrs = listOf(FindAttributesInput(attributes = listOf(FindAttributeInput(key = "k"))))
        val input = FindQueryInput(
            attributes = attrs,
            categoryIds = listOf(catId),
            collectionType = CollectionType.STANDARD,
            contentTypes = listOf("text/plain"),
            languageTags = listOf("en", "es"),
            extensionFilter = ExtensionFilterType.DOCUMENT,
            offset = 10L,
            limit = 25,
            ordering = emptyList(),
            traitIds = listOf("trait-1")
        )
        assertEquals(attrs, input.attributes)
        assertEquals(listOf(catId), input.categoryIds)
        assertEquals(CollectionType.STANDARD, input.collectionType)
        assertEquals(listOf("text/plain"), input.contentTypes)
        assertEquals(listOf("en", "es"), input.languageTags)
        assertEquals(ExtensionFilterType.DOCUMENT, input.extensionFilter)
        assertEquals(10L, input.offset)
        assertEquals(25, input.limit)
        assertEquals(emptyList(), input.ordering)
        assertEquals(listOf("trait-1"), input.traitIds)
    }

    @Test
    fun `data class equality`() {
        val a = FindQueryInput(contentTypes = listOf("image/png"), limit = 10)
        val b = FindQueryInput(contentTypes = listOf("image/png"), limit = 10)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes single field`() {
        val original = FindQueryInput(limit = 10)
        val modified = original.copy(limit = 50)
        assertEquals(50, modified.limit)
        assertNull(modified.contentTypes)
    }
}

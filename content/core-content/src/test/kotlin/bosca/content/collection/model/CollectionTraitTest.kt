package bosca.content.collection.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class CollectionTraitTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `stores all fields`() {
        val trait = CollectionTrait(collectionId = testId, traitId = "searchable")
        assertEquals(testId, trait.collectionId)
        assertEquals("searchable", trait.traitId)
    }

    @Test
    fun `data class equality`() {
        val a = CollectionTrait(collectionId = testId, traitId = "indexable")
        val b = CollectionTrait(collectionId = testId, traitId = "indexable")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality`() {
        val a = CollectionTrait(collectionId = testId, traitId = "searchable")
        val b = CollectionTrait(collectionId = testId, traitId = "indexable")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies traitId`() {
        val original = CollectionTrait(collectionId = testId, traitId = "searchable")
        val copied = original.copy(traitId = "indexable")
        assertEquals("indexable", copied.traitId)
        assertEquals(testId, copied.collectionId)
    }
}

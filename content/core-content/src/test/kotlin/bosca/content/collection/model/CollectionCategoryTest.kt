package bosca.content.collection.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class CollectionCategoryTest {

    @Test
    fun `stores all properties`() {
        val collectionId = Uuid.random()
        val categoryId = Uuid.random()

        val category = CollectionCategory(
            collectionId = collectionId,
            categoryId = categoryId
        )

        assertEquals(collectionId, category.collectionId)
        assertEquals(categoryId, category.categoryId)
    }

    @Test
    fun `equality based on all fields`() {
        val collectionId = Uuid.random()
        val categoryId = Uuid.random()
        val a = CollectionCategory(collectionId = collectionId, categoryId = categoryId)
        val b = CollectionCategory(collectionId = collectionId, categoryId = categoryId)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `inequality when fields differ`() {
        val collectionId = Uuid.random()
        val a = CollectionCategory(collectionId = collectionId, categoryId = Uuid.random())
        val b = CollectionCategory(collectionId = collectionId, categoryId = Uuid.random())
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves and overrides fields`() {
        val original = CollectionCategory(collectionId = Uuid.random(), categoryId = Uuid.random())
        val newCategoryId = Uuid.random()
        val copied = original.copy(categoryId = newCategoryId)
        assertEquals(newCategoryId, copied.categoryId)
        assertEquals(original.collectionId, copied.collectionId)
    }
}

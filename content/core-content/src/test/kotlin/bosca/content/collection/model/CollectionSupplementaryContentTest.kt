package bosca.content.collection.model

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class CollectionSupplementaryContentTest {

    private fun createCollection() = Collection(
        id = Uuid.random(),
        name = "Test Collection",
        languageTag = "en",
        workflowStateId = "draft"
    )

    private fun createSupplementary(key: String = "supp-key") = CollectionSupplementary(
        collectionId = Uuid.random(),
        key = key,
        name = "Supplementary",
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now()
    )

    @Test
    fun `stores collection and supplementary`() {
        val collection = createCollection()
        val supplementary = createSupplementary()
        val content = CollectionSupplementaryContent(
            collection = collection,
            supplementary = supplementary
        )
        assertEquals(collection, content.collection)
        assertEquals(supplementary, content.supplementary)
    }

    @Test
    fun `data class equality`() {
        val collection = createCollection()
        val supplementary = createSupplementary()
        val a = CollectionSupplementaryContent(collection = collection, supplementary = supplementary)
        val b = CollectionSupplementaryContent(collection = collection, supplementary = supplementary)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality`() {
        val collection = createCollection()
        val supp1 = createSupplementary("key-1")
        val supp2 = createSupplementary("key-2")
        val a = CollectionSupplementaryContent(collection = collection, supplementary = supp1)
        val b = CollectionSupplementaryContent(collection = collection, supplementary = supp2)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies supplementary`() {
        val collection = createCollection()
        val supp1 = createSupplementary("original")
        val supp2 = createSupplementary("updated")
        val original = CollectionSupplementaryContent(collection = collection, supplementary = supp1)
        val copied = original.copy(supplementary = supp2)
        assertEquals(supp2, copied.supplementary)
        assertEquals(collection, copied.collection)
    }
}

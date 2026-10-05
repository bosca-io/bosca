package bosca.content.collection.model

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class CollectionSupplementaryContentUrlsTest {

    private fun createCollection() = Collection(
        id = Uuid.random(),
        name = "Test Collection",
        languageTag = "en",
        workflowStateId = "draft"
    )

    private fun createSupplementary() = CollectionSupplementary(
        collectionId = Uuid.random(),
        key = "supp-key",
        name = "Supplementary",
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now()
    )

    @Test
    fun `stores collection and supplementary`() {
        val collection = createCollection()
        val supplementary = createSupplementary()
        val urls = CollectionSupplementaryContentUrls(
            collection = collection,
            supplementary = supplementary
        )
        assertEquals(collection, urls.collection)
        assertEquals(supplementary, urls.supplementary)
    }

    @Test
    fun `not a data class so uses reference equality`() {
        val collection = createCollection()
        val supplementary = createSupplementary()
        val a = CollectionSupplementaryContentUrls(collection = collection, supplementary = supplementary)
        val b = CollectionSupplementaryContentUrls(collection = collection, supplementary = supplementary)
        assert(a !== b)
    }
}

package bosca.content.collection.repository

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers the two plain data classes declared alongside the
 * [CollectionLanguageVariantRepository] interface. The interface itself is
 * KSP-generated (excluded from coverage); only these value holders carry
 * hand-written, coverable code.
 */
class CollectionLanguageVariantRepositoryCoverageTest {

    @Test
    fun `CollectionVariantBatchId exposes its constructor arguments`() {
        val ids = listOf(UUID.random(), UUID.random())
        val batch = CollectionVariantBatchId(ids = ids, languageTag = "en")

        assertEquals(ids, batch.ids)
        assertEquals("en", batch.languageTag)
    }

    @Test
    fun `CollectionVariantBatchId equality copy and hashCode`() {
        val ids = listOf(UUID.random())
        val a = CollectionVariantBatchId(ids, "en")
        val b = CollectionVariantBatchId(ids, "en")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        // copy with a changed field yields a different value
        val different = a.copy(languageTag = "fr")
        assertNotEquals(a, different)
        assertEquals(ids, different.ids)
        assertEquals("fr", different.languageTag)

        // copy with no changes reproduces an equal instance
        val same = a.copy()
        assertEquals(a, same)

        // destructuring / componentN
        val (destructuredIds, destructuredTag) = a
        assertEquals(ids, destructuredIds)
        assertEquals("en", destructuredTag)

        // toString mentions the fields
        assertTrue(a.toString().contains("languageTag=en"))
    }

    @Test
    fun `CollectionVariantId exposes its constructor arguments`() {
        val id = UUID.random()
        val variantId = CollectionVariantId(id = id, languageTag = "en")

        assertEquals(id, variantId.id)
        assertEquals("en", variantId.languageTag)
    }

    @Test
    fun `CollectionVariantId equality copy and hashCode`() {
        val id = UUID.random()
        val a = CollectionVariantId(id, "en")
        val b = CollectionVariantId(id, "en")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        // different id compares unequal
        val otherId = CollectionVariantId(UUID.random(), "en")
        assertNotEquals(a, otherId)

        // copy with a changed field yields a different value
        val different = a.copy(languageTag = "fr")
        assertNotEquals(a, different)
        assertEquals(id, different.id)
        assertEquals("fr", different.languageTag)

        // copy with no changes reproduces an equal instance
        val same = a.copy()
        assertEquals(a, same)

        // destructuring / componentN
        val (destructuredId, destructuredTag) = a
        assertEquals(id, destructuredId)
        assertEquals("en", destructuredTag)

        // toString mentions the fields
        assertTrue(a.toString().contains("languageTag=en"))
    }
}

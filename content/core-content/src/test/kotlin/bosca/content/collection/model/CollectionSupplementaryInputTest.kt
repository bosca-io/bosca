package bosca.content.collection.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CollectionSupplementaryInputTest {

    @Test
    fun `stores all properties`() {
        val collectionId = Uuid.random()
        val planId = Uuid.random()
        val jobId = Uuid.random()
        val attrs = buildJsonObject { put("key", JsonPrimitive("val")) }

        val input = CollectionSupplementaryInput(
            collectionId = collectionId,
            key = "supp-key",
            name = "Supplementary Name",
            planId = planId,
            jobId = jobId,
            contentType = "image/png",
            contentLength = 2048,
            sourceId = "source-123",
            sourceIdentifier = "ident-456",
            attributes = attrs
        )

        assertEquals(collectionId, input.collectionId)
        assertEquals("supp-key", input.key)
        assertEquals("Supplementary Name", input.name)
        assertEquals(planId, input.planId)
        assertEquals(jobId, input.jobId)
        assertEquals("image/png", input.contentType)
        assertEquals(2048, input.contentLength)
        assertEquals("source-123", input.sourceId)
        assertEquals("ident-456", input.sourceIdentifier)
        assertEquals(attrs, input.attributes)
    }

    @Test
    fun `default values`() {
        val input = CollectionSupplementaryInput(
            collectionId = Uuid.random(),
            key = "k",
            name = "n",
            contentType = "text/plain"
        )

        assertNull(input.planId)
        assertNull(input.jobId)
        assertNull(input.contentLength)
        assertNull(input.sourceId)
        assertNull(input.sourceIdentifier)
        assertNull(input.attributes)
    }

    @Test
    fun `equality based on all fields`() {
        val id = Uuid.random()
        val a = CollectionSupplementaryInput(collectionId = id, key = "k", name = "n", contentType = "ct")
        val b = CollectionSupplementaryInput(collectionId = id, key = "k", name = "n", contentType = "ct")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `inequality when fields differ`() {
        val id = Uuid.random()
        val a = CollectionSupplementaryInput(collectionId = id, key = "k", name = "n", contentType = "ct1")
        val b = CollectionSupplementaryInput(collectionId = id, key = "k", name = "n", contentType = "ct2")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves and overrides fields`() {
        val original = CollectionSupplementaryInput(
            collectionId = Uuid.random(),
            key = "k",
            name = "original",
            contentType = "text/plain"
        )
        val copied = original.copy(name = "copied", contentLength = 999)
        assertEquals("copied", copied.name)
        assertEquals(999, copied.contentLength)
        assertEquals(original.collectionId, copied.collectionId)
        assertEquals(original.key, copied.key)
        assertEquals(original.contentType, copied.contentType)
    }
}

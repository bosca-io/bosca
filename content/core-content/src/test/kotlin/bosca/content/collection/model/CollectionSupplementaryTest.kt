package bosca.content.collection.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CollectionSupplementaryTest {

    private val now = OffsetDateTime.now()

    @Test
    fun `stores all properties`() {
        val id = Uuid.random()
        val collectionId = Uuid.random()
        val planId = Uuid.random()
        val jobId = Uuid.random()
        val sourceId = Uuid.random()
        val attrs = JsonPrimitive("value")
        val uploaded = OffsetDateTime.now()

        val obj = CollectionSupplementary(
            id = id,
            collectionId = collectionId,
            key = "my-key",
            name = "My Supplementary",
            planId = planId,
            jobId = jobId,
            attributes = attrs,
            created = now,
            modified = now,
            uploaded = uploaded,
            contentType = "application/pdf",
            contentLength = 12345L,
            sourceId = sourceId,
            sourceIdentifier = "src-id-123"
        )

        assertEquals(id, obj.id)
        assertEquals(collectionId, obj.collectionId)
        assertEquals("my-key", obj.key)
        assertEquals("My Supplementary", obj.name)
        assertEquals(planId, obj.planId)
        assertEquals(jobId, obj.jobId)
        assertEquals(attrs, obj.attributes)
        assertEquals(now, obj.created)
        assertEquals(now, obj.modified)
        assertEquals(uploaded, obj.uploaded)
        assertEquals("application/pdf", obj.contentType)
        assertEquals(12345L, obj.contentLength)
        assertEquals(sourceId, obj.sourceId)
        assertEquals("src-id-123", obj.sourceIdentifier)
    }

    @Test
    fun `default values`() {
        val collectionId = Uuid.random()
        val obj = CollectionSupplementary(
            collectionId = collectionId,
            key = "k",
            name = "n",
            created = now,
            modified = now
        )

        assertEquals(Uuid.NIL, obj.id)
        assertNull(obj.planId)
        assertNull(obj.jobId)
        assertNull(obj.attributes)
        assertNull(obj.uploaded)
        assertNull(obj.contentType)
        assertNull(obj.contentLength)
        assertNull(obj.sourceId)
        assertNull(obj.sourceIdentifier)
    }

    @Test
    fun `equality based on all fields`() {
        val collectionId = Uuid.random()
        val a = CollectionSupplementary(collectionId = collectionId, key = "k", name = "n", created = now, modified = now)
        val b = CollectionSupplementary(collectionId = collectionId, key = "k", name = "n", created = now, modified = now)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `inequality when fields differ`() {
        val collectionId = Uuid.random()
        val a = CollectionSupplementary(collectionId = collectionId, key = "k1", name = "n", created = now, modified = now)
        val b = CollectionSupplementary(collectionId = collectionId, key = "k2", name = "n", created = now, modified = now)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves and overrides fields`() {
        val collectionId = Uuid.random()
        val original = CollectionSupplementary(
            collectionId = collectionId,
            key = "k",
            name = "original",
            created = now,
            modified = now
        )
        val copied = original.copy(name = "copied", contentType = "text/plain")
        assertEquals("copied", copied.name)
        assertEquals("text/plain", copied.contentType)
        assertEquals(original.collectionId, copied.collectionId)
        assertEquals(original.key, copied.key)
    }

    @Test
    fun `toId produces correct SupplementaryIdObject`() {
        val id = Uuid.random()
        val collectionId = Uuid.random()
        val planId = Uuid.random()
        val jobId = Uuid.random()
        val obj = CollectionSupplementary(
            id = id,
            collectionId = collectionId,
            key = "the-key",
            name = "n",
            planId = planId,
            jobId = jobId,
            created = now,
            modified = now
        )
        val result = obj.toId()
        assertEquals(collectionId, result.contentId)
        assertEquals(id, result.id)
        assertEquals("the-key", result.key)
        assertEquals(planId, result.planId)
        assertEquals(jobId, result.jobId)
    }
}

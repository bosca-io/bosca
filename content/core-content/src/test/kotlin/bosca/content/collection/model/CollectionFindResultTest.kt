package bosca.content.collection.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CollectionFindResultTest {

    @Test
    fun `stores all fields`() {
        val collectionId = Uuid.random()
        val metadataId = Uuid.random()
        val attrs = JsonObject(mapOf("key" to JsonPrimitive("value")))

        val result = CollectionFindResult(
            childCollectionId = collectionId,
            childMetadataId = metadataId,
            attributes = attrs
        )

        assertEquals(collectionId, result.childCollectionId)
        assertEquals(metadataId, result.childMetadataId)
        assertEquals(attrs, result.attributes)
    }

    @Test
    fun `all fields can be null`() {
        val result = CollectionFindResult(
            childCollectionId = null,
            childMetadataId = null,
            attributes = null
        )

        assertNull(result.childCollectionId)
        assertNull(result.childMetadataId)
        assertNull(result.attributes)
    }

    @Test
    fun `data class equality`() {
        val id = Uuid.random()
        val a = CollectionFindResult(childCollectionId = id, childMetadataId = null, attributes = null)
        val b = CollectionFindResult(childCollectionId = id, childMetadataId = null, attributes = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality`() {
        val a = CollectionFindResult(childCollectionId = Uuid.random(), childMetadataId = null, attributes = null)
        val b = CollectionFindResult(childCollectionId = Uuid.random(), childMetadataId = null, attributes = null)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies fields`() {
        val original = CollectionFindResult(
            childCollectionId = Uuid.random(),
            childMetadataId = null,
            attributes = null
        )
        val newMetadataId = Uuid.random()
        val copied = original.copy(childMetadataId = newMetadataId)
        assertEquals(newMetadataId, copied.childMetadataId)
        assertEquals(original.childCollectionId, copied.childCollectionId)
    }
}

package bosca.content.collection.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CollectionItemTest {

    @Test
    fun `CollectionItem defaults`() {
        val collectionId = Uuid.random()
        val item = CollectionItem(collectionId = collectionId)
        assertEquals(0, item.id)
        assertEquals(collectionId, item.collectionId)
        assertNull(item.childCollectionId)
        assertNull(item.childMetadataId)
        assertNull(item.attributes)
    }

    @Test
    fun `CollectionItem stores all properties`() {
        val collectionId = Uuid.random()
        val childCollectionId = Uuid.random()
        val childMetadataId = Uuid.random()
        val attrs = JsonPrimitive("test")
        val item = CollectionItem(
            id = 42,
            collectionId = collectionId,
            childCollectionId = childCollectionId,
            childMetadataId = childMetadataId,
            attributes = attrs
        )
        assertEquals(42, item.id)
        assertEquals(childCollectionId, item.childCollectionId)
        assertEquals(childMetadataId, item.childMetadataId)
        assertEquals(attrs, item.attributes)
    }
}

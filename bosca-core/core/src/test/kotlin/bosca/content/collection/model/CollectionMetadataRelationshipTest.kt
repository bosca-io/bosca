package bosca.content.collection.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CollectionMetadataRelationshipTest {

    private val collId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val metaId = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")

    @Test
    fun fieldsArePreserved() {
        val rel = CollectionMetadataRelationship(
            collectionId = collId,
            metadataId = metaId,
            relationship = "contains"
        )
        assertEquals(collId, rel.collectionId)
        assertEquals(metaId, rel.metadataId)
        assertEquals("contains", rel.relationship)
    }

    @Test
    fun id1ReturnsCollectionId() {
        val rel = CollectionMetadataRelationship(collectionId = collId, metadataId = metaId, relationship = "ref")
        assertEquals(collId, rel.id1)
    }

    @Test
    fun id2ReturnsMetadataId() {
        val rel = CollectionMetadataRelationship(collectionId = collId, metadataId = metaId, relationship = "ref")
        assertEquals(metaId, rel.id2)
    }

    @Test
    fun attributesDefaultToNull() {
        val rel = CollectionMetadataRelationship(collectionId = collId, metadataId = metaId, relationship = "ref")
        assertNull(rel.attributes)
    }

    @Test
    fun dataClassEquality() {
        val a = CollectionMetadataRelationship(collectionId = collId, metadataId = metaId, relationship = "ref")
        val b = CollectionMetadataRelationship(collectionId = collId, metadataId = metaId, relationship = "ref")
        assertEquals(a, b)
    }
}

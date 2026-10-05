package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class MetadataRelationshipTest {

    @Test
    fun `MetadataRelationship implements ContentRelationship`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()
        val rel = MetadataRelationship(
            metadataId1 = id1,
            metadataId2 = id2,
            relationship = "translation"
        )
        assertEquals(id1, rel.id1)
        assertEquals(id2, rel.id2)
        assertEquals("translation", rel.relationship)
        assertNull(rel.attributes)
    }

    @Test
    fun `MetadataRelationship id1 and id2 delegate to metadataId fields`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()
        val rel = MetadataRelationship(
            metadataId1 = id1,
            metadataId2 = id2,
            relationship = "related"
        )
        assertEquals(rel.metadataId1, rel.id1)
        assertEquals(rel.metadataId2, rel.id2)
    }
}

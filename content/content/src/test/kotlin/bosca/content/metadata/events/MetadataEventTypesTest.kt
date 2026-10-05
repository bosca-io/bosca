package bosca.content.metadata.events

import bosca.content.metadata.model.MetadataRelationship
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataEventTypesTest {

    @Test
    fun `MetadataCreated field preservation`() {
        val id = UUID.random()
        val event = MetadataCreated(id = id, version = 3)
        assertEquals(id, event.id)
        assertEquals(3, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataUpdated field preservation`() {
        val id = UUID.random()
        val event = MetadataUpdated(id = id, version = 5)
        assertEquals(id, event.id)
        assertEquals(5, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataUpdated version defaults to zero`() {
        val id = UUID.random()
        val event = MetadataUpdated(id = id)
        assertEquals(0, event.version)
    }

    @Test
    fun `MetadataRelationshipAdded carries relationship`() {
        val id = UUID.random()
        val rel = MetadataRelationship(
            metadataId1 = UUID.random(),
            metadataId2 = UUID.random(),
            relationship = "related"
        )
        val event = MetadataRelationshipAdded(id = id, version = 1, relationship = rel)
        assertEquals(id, event.id)
        assertEquals(1, event.version)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataRelationshipMerged carries relationship`() {
        val id = UUID.random()
        val rel = MetadataRelationship(
            metadataId1 = UUID.random(),
            metadataId2 = UUID.random(),
            relationship = "merged"
        )
        val event = MetadataRelationshipMerged(id = id, version = 2, relationship = rel)
        assertEquals(id, event.id)
        assertEquals(rel, event.relationship)
    }

    @Test
    fun `MetadataRelationshipRemoved carries relationship`() {
        val id = UUID.random()
        val rel = MetadataRelationship(
            metadataId1 = UUID.random(),
            metadataId2 = UUID.random(),
            relationship = "removed"
        )
        val event = MetadataRelationshipRemoved(id = id, version = 3, relationship = rel)
        assertEquals(id, event.id)
        assertEquals(rel, event.relationship)
    }

    @Test
    fun `MetadataStateChanged field preservation`() {
        val id = UUID.random()
        val event = MetadataStateChanged(id = id, version = 1)
        assertEquals(id, event.id)
        assertEquals(1, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataStateChangeComplete field preservation`() {
        val id = UUID.random()
        val event = MetadataStateChangeComplete(id = id, version = 2)
        assertEquals(id, event.id)
        assertEquals(2, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataSetTraits field preservation`() {
        val id = UUID.random()
        val event = MetadataSetTraits(id = id, version = 4)
        assertEquals(id, event.id)
        assertEquals(4, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataUploadCleared field preservation`() {
        val id = UUID.random()
        val event = MetadataUploadCleared(id = id, version = 1)
        assertEquals(id, event.id)
        assertEquals(1, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataDeleted field preservation`() {
        val id = UUID.random()
        val event = MetadataDeleted(id = id, version = 3)
        assertEquals(id, event.id)
        assertEquals(3, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataSetReady field preservation`() {
        val id = UUID.random()
        val event = MetadataSetReady(id = id, version = 1)
        assertEquals(id, event.id)
        assertEquals(1, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataSetNotReady field preservation`() {
        val id = UUID.random()
        val event = MetadataSetNotReady(id = id, version = 2)
        assertEquals(id, event.id)
        assertEquals(2, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataUploadedEvent field preservation`() {
        val id = UUID.random()
        val event = MetadataUploadedEvent(id = id, version = 1)
        assertEquals(id, event.id)
        assertEquals(1, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataSupplementedUpdatedEvent carries supplementaryId`() {
        val id = UUID.random()
        val suppId = UUID.random()
        val event = MetadataSupplementedUpdatedEvent(id = id, version = 1, supplementaryId = suppId)
        assertEquals(id, event.id)
        assertEquals(1, event.version)
        assertEquals(suppId, event.supplementaryId)
    }

    @Test
    fun `MetadataLockedEvent field preservation`() {
        val id = UUID.random()
        val event = MetadataLockedEvent(id = id, version = 1)
        assertEquals(id, event.id)
        assertEquals(1, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataUnlockedEvent field preservation`() {
        val id = UUID.random()
        val event = MetadataUnlockedEvent(id = id, version = 2)
        assertEquals(id, event.id)
        assertEquals(2, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `channel constants have expected values`() {
        assertEquals("bosca.content.metadata.created", METADATA_CREATED_CHANNEL)
        assertEquals("bosca.content.metadata.updated", METADATA_UPDATED_CHANNEL)
        assertEquals("bosca.content.metadata.relationship.added", METADATA_RELATIONSHIP_ADDED_CHANNEL)
        assertEquals("bosca.content.metadata.state", METADATA_STATE_CHANNEL)
    }
}

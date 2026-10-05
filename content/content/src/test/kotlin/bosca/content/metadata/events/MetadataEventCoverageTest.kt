package bosca.content.metadata.events

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataType
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Coverage-focused companion to [MetadataEventTest] / [MetadataEventTypesTest].
 *
 * Those tests exercise the primary constructors of every event class. This file covers
 * what they do NOT: the [Metadata]-based secondary constructors on each event, the
 * relationship / supplementaryId secondary constructors, and the [MetadataEvent.identityKey]
 * override which builds a `Triple(id, version, supplementaryId)` from each concrete event.
 */
class MetadataEventCoverageTest {

    private fun metadata(id: UUID, version: Int): Metadata = Metadata(
        id = id,
        version = version,
        name = "sample",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 0L,
        languageTag = "en",
        workflowStateId = "unknown"
    )

    private fun relationship(): MetadataRelationship = MetadataRelationship(
        metadataId1 = UUID.random(),
        metadataId2 = UUID.random(),
        relationship = "related"
    )

    // ---- Metadata secondary constructor coverage (simple id/version events) ----

    @Test
    fun `MetadataCreated from metadata`() {
        val id = UUID.random()
        val event = MetadataCreated(metadata(id, 7))
        assertEquals(id, event.id)
        assertEquals(7, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataUpdated from metadata`() {
        val id = UUID.random()
        val event = MetadataUpdated(metadata(id, 4))
        assertEquals(id, event.id)
        assertEquals(4, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataStateChanged from metadata`() {
        val id = UUID.random()
        val event = MetadataStateChanged(metadata(id, 2))
        assertEquals(id, event.id)
        assertEquals(2, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataStateChangeComplete from metadata`() {
        val id = UUID.random()
        val event = MetadataStateChangeComplete(metadata(id, 9))
        assertEquals(id, event.id)
        assertEquals(9, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataSetTraits from metadata`() {
        val id = UUID.random()
        val event = MetadataSetTraits(metadata(id, 3))
        assertEquals(id, event.id)
        assertEquals(3, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataUploadCleared from metadata`() {
        val id = UUID.random()
        val event = MetadataUploadCleared(metadata(id, 1))
        assertEquals(id, event.id)
        assertEquals(1, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataDeleted from metadata`() {
        val id = UUID.random()
        val event = MetadataDeleted(metadata(id, 6))
        assertEquals(id, event.id)
        assertEquals(6, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataSetReady from metadata`() {
        val id = UUID.random()
        val event = MetadataSetReady(metadata(id, 5))
        assertEquals(id, event.id)
        assertEquals(5, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataSetNotReady from metadata`() {
        val id = UUID.random()
        val event = MetadataSetNotReady(metadata(id, 8))
        assertEquals(id, event.id)
        assertEquals(8, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataUploadedEvent from metadata`() {
        val id = UUID.random()
        val event = MetadataUploadedEvent(metadata(id, 2))
        assertEquals(id, event.id)
        assertEquals(2, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataLockedEvent from metadata`() {
        val id = UUID.random()
        val event = MetadataLockedEvent(metadata(id, 1))
        assertEquals(id, event.id)
        assertEquals(1, event.version)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataUnlockedEvent from metadata`() {
        val id = UUID.random()
        val event = MetadataUnlockedEvent(metadata(id, 3))
        assertEquals(id, event.id)
        assertEquals(3, event.version)
        assertNull(event.supplementaryId)
    }

    // ---- Metadata + relationship secondary constructor coverage ----

    @Test
    fun `MetadataRelationshipAdded from metadata and relationship`() {
        val id = UUID.random()
        val rel = relationship()
        val event = MetadataRelationshipAdded(metadata(id, 4), rel)
        assertEquals(id, event.id)
        assertEquals(4, event.version)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataRelationshipMerged from metadata and relationship`() {
        val id = UUID.random()
        val rel = relationship()
        val event = MetadataRelationshipMerged(metadata(id, 5), rel)
        assertEquals(id, event.id)
        assertEquals(5, event.version)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `MetadataRelationshipRemoved from metadata and relationship`() {
        val id = UUID.random()
        val rel = relationship()
        val event = MetadataRelationshipRemoved(metadata(id, 6), rel)
        assertEquals(id, event.id)
        assertEquals(6, event.version)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    // ---- Metadata + supplementaryId secondary constructor coverage ----

    @Test
    fun `MetadataSupplementedUpdatedEvent from metadata and supplementaryId`() {
        val id = UUID.random()
        val suppId = UUID.random()
        val event = MetadataSupplementedUpdatedEvent(metadata(id, 3), suppId)
        assertEquals(id, event.id)
        assertEquals(3, event.version)
        assertEquals(suppId, event.supplementaryId)
    }

    // ---- identityKey() coverage (MetadataEvent default; Triple(id, version, supplementaryId)) ----

    @Test
    fun `identityKey collapses id version and null supplementaryId`() {
        val id = UUID.random()
        val event = MetadataCreated(id = id, version = 2)
        assertEquals(Triple(id, 2, null), event.identityKey())
    }

    @Test
    fun `identityKey for relationship event ignores relationship`() {
        val id = UUID.random()
        val event = MetadataRelationshipAdded(id = id, version = 1, relationship = relationship())
        assertEquals(Triple(id, 1, null), event.identityKey())
    }

    @Test
    fun `identityKey for supplemented event carries supplementaryId`() {
        val id = UUID.random()
        val suppId = UUID.random()
        val event = MetadataSupplementedUpdatedEvent(id = id, version = 4, supplementaryId = suppId)
        assertEquals(Triple(id, 4, suppId), event.identityKey())
    }

    @Test
    fun `equal simple events produce equal identity keys`() {
        val id = UUID.random()
        val a = MetadataDeleted(id = id, version = 3)
        val b = MetadataDeleted(id = id, version = 3)
        assertEquals(a.identityKey(), b.identityKey())
    }

    @Test
    fun `differing version yields differing identity key`() {
        val id = UUID.random()
        val a = MetadataUpdated(id = id, version = 1)
        val b = MetadataUpdated(id = id, version = 2)
        val keyA = a.identityKey()
        val keyB = b.identityKey()
        assertEquals(false, keyA == keyB)
    }
}

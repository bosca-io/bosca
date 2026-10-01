package bosca.content.collection.events

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.serialization.UUID
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the secondary (domain-model) constructors and the [CollectionEvent.identityKey]
 * default that the primary-constructor focused sibling tests do not exercise.
 */
class CollectionEventCoverageTest {

    private fun collection(id: UUID = UUID.random()) = Collection(
        id = id,
        name = "test",
        languageTag = "en",
        workflowStateId = "draft"
    )

    private fun variant(id: UUID = UUID.random(), languageTag: String = "fr") =
        CollectionLanguageVariant(
            id = id,
            languageTag = languageTag,
            name = "Variant"
        )

    private fun metadata() = Metadata(
        name = "meta",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "pending",
        attributes = null,
    )

    private fun supplementary(collectionId: UUID = UUID.random(), id: UUID = UUID.random()) =
        CollectionSupplementary(
            id = id,
            collectionId = collectionId,
            key = "thumbnail",
            name = "Thumbnail",
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now(),
        )

    @Test
    fun `CollectionMetadataRelationshipAdded from collection copies id`() {
        val col = collection()
        val rel = CollectionMetadataRelationship(
            collectionId = col.id,
            metadataId = UUID.random(),
            relationship = "contains"
        )
        val event = CollectionMetadataRelationshipAdded(col, rel)
        assertEquals(col.id, event.id)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionMetadataRelationshipMerged from collection copies id`() {
        val col = collection()
        val rel = CollectionMetadataRelationship(
            collectionId = col.id,
            metadataId = UUID.random(),
            relationship = "merged"
        )
        val event = CollectionMetadataRelationshipMerged(col, rel)
        assertEquals(col.id, event.id)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionMetadataRelationshipRemoved from collection copies id`() {
        val col = collection()
        val rel = CollectionMetadataRelationship(
            collectionId = col.id,
            metadataId = UUID.random(),
            relationship = "removed"
        )
        val event = CollectionMetadataRelationshipRemoved(col, rel)
        assertEquals(col.id, event.id)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionLanguageVariantMetadataRelationshipAdded from variant copies id and languageTag`() {
        val v = variant(languageTag = "es")
        val rel = CollectionLanguageVariantMetadataRelationship(
            collectionId = v.id,
            metadataId = UUID.random(),
            languageTag = "es",
            relationship = "translates"
        )
        val event = CollectionLanguageVariantMetadataRelationshipAdded(v, rel)
        assertEquals(v.id, event.id)
        assertEquals("es", event.languageTag)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionLanguageVariantMetadataRelationshipMerged from variant copies id and languageTag`() {
        val v = variant(languageTag = "de")
        val rel = CollectionLanguageVariantMetadataRelationship(
            collectionId = v.id,
            metadataId = UUID.random(),
            languageTag = "de",
            relationship = "merged"
        )
        val event = CollectionLanguageVariantMetadataRelationshipMerged(v, rel)
        assertEquals(v.id, event.id)
        assertEquals("de", event.languageTag)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionLanguageVariantMetadataRelationshipRemoved from variant copies id and languageTag`() {
        val v = variant(languageTag = "pt")
        val rel = CollectionLanguageVariantMetadataRelationship(
            collectionId = v.id,
            metadataId = UUID.random(),
            languageTag = "pt",
            relationship = "removed"
        )
        val event = CollectionLanguageVariantMetadataRelationshipRemoved(v, rel)
        assertEquals(v.id, event.id)
        assertEquals("pt", event.languageTag)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionMetadataItemAdded from collection and metadata copies ids`() {
        val col = collection()
        val meta = metadata()
        val metaId = meta.id
        val event = CollectionMetadataItemAdded(col, meta)
        assertEquals(col.id, event.id)
        assertEquals(metaId, event.metadataId)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionMetadataItemRemoved from collection and metadata copies ids`() {
        val col = collection()
        val meta = metadata()
        val metaId = meta.id
        val event = CollectionMetadataItemRemoved(col, meta)
        assertEquals(col.id, event.id)
        assertEquals(metaId, event.metadataId)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionSupplementaryAdded from supplementary copies collectionId and id`() {
        val collectionId = UUID.random()
        val suppId = UUID.random()
        val supp = supplementary(collectionId = collectionId, id = suppId)
        val event = CollectionSupplementaryAdded(supp)
        assertEquals(collectionId, event.id)
        assertEquals(suppId, event.supplementaryId)
    }

    @Test
    fun `CollectionSupplementaryUpdated from supplementary copies collectionId and id`() {
        val collectionId = UUID.random()
        val suppId = UUID.random()
        val supp = supplementary(collectionId = collectionId, id = suppId)
        val event = CollectionSupplementaryUpdated(supp)
        assertEquals(collectionId, event.id)
        assertEquals(suppId, event.supplementaryId)
    }

    @Test
    fun `CollectionStateChanged from Collection has null languageTag`() {
        val col = collection()
        val event = CollectionStateChanged(col)
        assertEquals(col.id, event.id)
        assertNull(event.languageTag)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionStateChanged from CollectionLanguageVariant carries languageTag`() {
        val v = variant(languageTag = "fr")
        val event = CollectionStateChanged(v)
        assertEquals(v.id, event.id)
        assertEquals("fr", event.languageTag)
    }

    @Test
    fun `CollectionStateChangedComplete from Collection has null languageTag`() {
        val col = collection()
        val event = CollectionStateChangedComplete(col)
        assertEquals(col.id, event.id)
        assertNull(event.languageTag)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionStateChangedComplete from CollectionLanguageVariant carries languageTag`() {
        val v = variant(languageTag = "it")
        val event = CollectionStateChangedComplete(v)
        assertEquals(v.id, event.id)
        assertEquals("it", event.languageTag)
    }

    @Test
    fun `CollectionSupplementedUpdatedEvent from collection and supplementaryId copies both`() {
        val col = collection()
        val suppId = UUID.random()
        val event = CollectionSupplementedUpdatedEvent(col, suppId)
        assertEquals(col.id, event.id)
        assertEquals(suppId, event.supplementaryId)
    }

    @Test
    fun `CollectionLockedEvent from collection copies id`() {
        val col = collection()
        val event = CollectionLockedEvent(col)
        assertEquals(col.id, event.id)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionUnlockedEvent from collection copies id`() {
        val col = collection()
        val event = CollectionUnlockedEvent(col)
        assertEquals(col.id, event.id)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `identityKey pairs id with null supplementaryId`() {
        val id = UUID.random()
        val event = CollectionLockedEvent(id = id)
        assertEquals(id to null, event.identityKey())
    }

    @Test
    fun `identityKey pairs id with non-null supplementaryId`() {
        val id = UUID.random()
        val suppId = UUID.random()
        val event = CollectionSupplementaryAdded(id = id, supplementaryId = suppId)
        assertEquals(id to suppId, event.identityKey())
    }
}

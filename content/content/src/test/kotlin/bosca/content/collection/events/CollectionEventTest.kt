package bosca.content.collection.events

import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionEventTest {

    @Test
    fun `CollectionCreated carries id and null supplementaryId`() {
        val id = UUID.random()
        val event = CollectionCreated(id = id)
        assertEquals(id, event.id)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionMetadataRelationshipAdded carries id and relationship`() {
        val id = UUID.random()
        val rel = CollectionMetadataRelationship(
            collectionId = id,
            metadataId = UUID.random(),
            relationship = "contains"
        )
        val event = CollectionMetadataRelationshipAdded(id = id, relationship = rel)
        assertEquals(id, event.id)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionLanguageVariantMetadataRelationshipAdded carries id, languageTag and relationship`() {
        val id = UUID.random()
        val rel = CollectionLanguageVariantMetadataRelationship(
            collectionId = id,
            metadataId = UUID.random(),
            languageTag = "es",
            relationship = "translates"
        )
        val event = CollectionLanguageVariantMetadataRelationshipAdded(
            id = id,
            languageTag = "es",
            relationship = rel
        )
        assertEquals(id, event.id)
        assertEquals("es", event.languageTag)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionMetadataRelationshipMerged carries id and relationship`() {
        val id = UUID.random()
        val rel = CollectionMetadataRelationship(
            collectionId = id,
            metadataId = UUID.random(),
            relationship = "merged"
        )
        val event = CollectionMetadataRelationshipMerged(id = id, relationship = rel)
        assertEquals(id, event.id)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionLanguageVariantMetadataRelationshipMerged carries id, languageTag and relationship`() {
        val id = UUID.random()
        val rel = CollectionLanguageVariantMetadataRelationship(
            collectionId = id,
            metadataId = UUID.random(),
            languageTag = "fr",
            relationship = "merged"
        )
        val event = CollectionLanguageVariantMetadataRelationshipMerged(
            id = id,
            languageTag = "fr",
            relationship = rel
        )
        assertEquals(id, event.id)
        assertEquals("fr", event.languageTag)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionMetadataRelationshipRemoved carries id and relationship`() {
        val id = UUID.random()
        val rel = CollectionMetadataRelationship(
            collectionId = id,
            metadataId = UUID.random(),
            relationship = "removed"
        )
        val event = CollectionMetadataRelationshipRemoved(id = id, relationship = rel)
        assertEquals(id, event.id)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionLanguageVariantMetadataRelationshipRemoved carries id, languageTag and relationship`() {
        val id = UUID.random()
        val rel = CollectionLanguageVariantMetadataRelationship(
            collectionId = id,
            metadataId = UUID.random(),
            languageTag = "de",
            relationship = "removed"
        )
        val event = CollectionLanguageVariantMetadataRelationshipRemoved(
            id = id,
            languageTag = "de",
            relationship = rel
        )
        assertEquals(id, event.id)
        assertEquals("de", event.languageTag)
        assertEquals(rel, event.relationship)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionMetadataItemAdded carries id and metadataId`() {
        val id = UUID.random()
        val metaId = UUID.random()
        val event = CollectionMetadataItemAdded(id = id, metadataId = metaId)
        assertEquals(id, event.id)
        assertEquals(metaId, event.metadataId)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionMetadataItemRemoved carries id and metadataId`() {
        val id = UUID.random()
        val metaId = UUID.random()
        val event = CollectionMetadataItemRemoved(id = id, metadataId = metaId)
        assertEquals(id, event.id)
        assertEquals(metaId, event.metadataId)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionSupplementaryAdded carries id and supplementaryId`() {
        val id = UUID.random()
        val suppId = UUID.random()
        val event = CollectionSupplementaryAdded(id = id, supplementaryId = suppId)
        assertEquals(id, event.id)
        assertEquals(suppId, event.supplementaryId)
    }

    @Test
    fun `CollectionSupplementaryUpdated carries id and supplementaryId`() {
        val id = UUID.random()
        val suppId = UUID.random()
        val event = CollectionSupplementaryUpdated(id = id, supplementaryId = suppId)
        assertEquals(id, event.id)
        assertEquals(suppId, event.supplementaryId)
    }

    @Test
    fun `CollectionDeleted carries id and null supplementaryId`() {
        val id = UUID.random()
        val event = CollectionDeleted(id = id)
        assertEquals(id, event.id)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionSetReady carries id with optional languageTag`() {
        val id = UUID.random()
        val event = CollectionSetReady(id = id, languageTag = "en")
        assertEquals(id, event.id)
        assertEquals("en", event.languageTag)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionSetReady languageTag defaults to null`() {
        val event = CollectionSetReady(id = UUID.random())
        assertNull(event.languageTag)
    }

    @Test
    fun `CollectionSetNotReady carries id with optional languageTag`() {
        val id = UUID.random()
        val event = CollectionSetNotReady(id = id, languageTag = "pt")
        assertEquals(id, event.id)
        assertEquals("pt", event.languageTag)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionSetNotReady languageTag defaults to null`() {
        val event = CollectionSetNotReady(id = UUID.random())
        assertNull(event.languageTag)
    }

    @Test
    fun `CollectionSupplementedUpdatedEvent carries id and supplementaryId`() {
        val id = UUID.random()
        val suppId = UUID.random()
        val event = CollectionSupplementedUpdatedEvent(id = id, supplementaryId = suppId)
        assertEquals(id, event.id)
        assertEquals(suppId, event.supplementaryId)
    }

    @Test
    fun `CollectionLockedEvent carries id and null supplementaryId`() {
        val id = UUID.random()
        val event = CollectionLockedEvent(id = id)
        assertEquals(id, event.id)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `CollectionUnlockedEvent carries id and null supplementaryId`() {
        val id = UUID.random()
        val event = CollectionUnlockedEvent(id = id)
        assertEquals(id, event.id)
        assertNull(event.supplementaryId)
    }

    @Test
    fun `channel constants have expected values`() {
        assertEquals("bosca.content.collection.created", COLLECTION_CREATED_CHANNEL)
        assertEquals("bosca.content.collection.updated", COLLECTION_UPDATED_CHANNEL)
        assertEquals("bosca.content.collection.state", COLLECTION_STATE_CHANNEL)
    }
}

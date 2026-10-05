package bosca.content.collection.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CollectionInputModelsTest {

    // --- CollectionChildInput ---

    @Test
    fun `CollectionChildInput defaults to null`() {
        val input = CollectionChildInput()
        assertNull(input.attributes)
        assertNull(input.collection)
    }

    // --- MetadataChildInput ---

    @Test
    fun `MetadataChildInput defaults to null`() {
        val input = MetadataChildInput()
        assertNull(input.attributes)
        assertNull(input.metadata)
    }

    // --- CollectionWorkflowInput ---

    @Test
    fun `CollectionWorkflowInput stores state`() {
        val input = CollectionWorkflowInput(state = "published")
        assertEquals("published", input.state)
        assertNull(input.deleteWorkflowId)
    }

    @Test
    fun `CollectionWorkflowInput stores deleteWorkflowId`() {
        val id = Uuid.random()
        val input = CollectionWorkflowInput(state = "draft", deleteWorkflowId = id)
        assertEquals("draft", input.state)
        assertEquals(id, input.deleteWorkflowId)
    }

    // --- CollectionSupplementaryInput ---

    @Test
    fun `CollectionSupplementaryInput stores required fields and defaults`() {
        val id = Uuid.random()
        val input = CollectionSupplementaryInput(
            collectionId = id,
            key = "thumbnail",
            name = "Thumbnail",
            contentType = "image/png"
        )
        assertEquals(id, input.collectionId)
        assertEquals("thumbnail", input.key)
        assertEquals("Thumbnail", input.name)
        assertEquals("image/png", input.contentType)
        assertNull(input.planId)
        assertNull(input.jobId)
        assertNull(input.contentLength)
        assertNull(input.sourceId)
        assertNull(input.sourceIdentifier)
        assertNull(input.attributes)
    }

    // --- CollectionTrait ---

    @Test
    fun `CollectionTrait stores collectionId and traitId`() {
        val id = Uuid.random()
        val trait = CollectionTrait(collectionId = id, traitId = "featured")
        assertEquals(id, trait.collectionId)
        assertEquals("featured", trait.traitId)
    }

    // --- CollectionCategory ---

    @Test
    fun `CollectionCategory stores collectionId and categoryId`() {
        val colId = Uuid.random()
        val catId = Uuid.random()
        val cc = CollectionCategory(collectionId = colId, categoryId = catId)
        assertEquals(colId, cc.collectionId)
        assertEquals(catId, cc.categoryId)
    }

    // --- CollectionFindResult ---

    @Test
    fun `CollectionFindResult stores nullable fields`() {
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
    fun `CollectionFindResult stores populated fields`() {
        val colId = Uuid.random()
        val metaId = Uuid.random()
        val result = CollectionFindResult(
            childCollectionId = colId,
            childMetadataId = metaId,
            attributes = null
        )
        assertEquals(colId, result.childCollectionId)
        assertEquals(metaId, result.childMetadataId)
    }

    // --- CollectionMetadataRelationshipInput ---

    @Test
    fun `CollectionMetadataRelationshipInput defaults`() {
        val id = Uuid.random()
        val metaId = Uuid.random()
        val input = CollectionMetadataRelationshipInput(id = id, metadataId = metaId)
        assertEquals(id, input.id)
        assertEquals(metaId, input.metadataId)
        assertNull(input.languageTag)
        assertNull(input.relationship)
        assertNull(input.attributes)
    }

    // --- CollectionCollaborationInput ---

    @Test
    fun `CollectionCollaborationInput stores properties`() {
        val id = Uuid.random()
        val content = byteArrayOf(1, 2, 3)
        val input = CollectionCollaborationInput(
            collectionId = id,
            languageTag = "en",
            content = content
        )
        assertEquals(id, input.collectionId)
        assertEquals("en", input.languageTag)
        assertEquals(3, input.content.size)
    }
}

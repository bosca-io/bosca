package bosca.content.collection.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionModelTest {

    private fun createCollection(
        workflowStateId: String = "draft",
        deleted: Boolean = false,
        searchable: Boolean = true
    ) = Collection(
        id = UUID.random(),
        name = "Test Collection",
        languageTag = "en",
        workflowStateId = workflowStateId,
        deleted = deleted,
        searchable = searchable
    )

    // --- Collection isPublished ---

    @Test
    fun `collection isPublished true when state is published`() {
        val collection = createCollection(workflowStateId = "published")
        assertTrue(collection.isPublished)
    }

    @Test
    fun `collection isPublished false when state is draft`() {
        val collection = createCollection(workflowStateId = "draft")
        assertFalse(collection.isPublished)
    }

    @Test
    fun `collection isPublished false when state is advertised`() {
        val collection = createCollection(workflowStateId = "advertised")
        assertFalse(collection.isPublished)
    }

    @Test
    fun `collection isPublished false when state is review`() {
        val collection = createCollection(workflowStateId = "review")
        assertFalse(collection.isPublished)
    }

    @Test
    fun `collection isPublished false when state is pending`() {
        val collection = createCollection(workflowStateId = "pending")
        assertFalse(collection.isPublished)
    }

    // --- Collection isAdvertised ---

    @Test
    fun `collection isAdvertised true when state is advertised`() {
        val collection = createCollection(workflowStateId = "advertised")
        assertTrue(collection.isAdvertised)
    }

    @Test
    fun `collection isAdvertised false when state is published`() {
        val collection = createCollection(workflowStateId = "published")
        assertFalse(collection.isAdvertised)
    }

    @Test
    fun `collection isAdvertised false when state is draft`() {
        val collection = createCollection(workflowStateId = "draft")
        assertFalse(collection.isAdvertised)
    }

    // --- Collection isDeleted ---

    @Test
    fun `collection isDeleted true when deleted flag is true`() {
        val collection = createCollection(deleted = true)
        assertTrue(collection.isDeleted)
    }

    @Test
    fun `collection isDeleted false when deleted flag is false`() {
        val collection = createCollection(deleted = false)
        assertFalse(collection.isDeleted)
    }

    // --- Collection isSearchable ---

    @Test
    fun `collection isSearchable true when searchable is true`() {
        val collection = createCollection(searchable = true)
        assertTrue(collection.isSearchable)
    }

    @Test
    fun `collection isSearchable false when searchable is false`() {
        val collection = createCollection(searchable = false)
        assertFalse(collection.isSearchable)
    }

    // --- Collection defaults ---

    @Test
    fun `collection publicContent defaults to false`() {
        val collection = createCollection()
        assertFalse(collection.publicContent)
    }

    @Test
    fun `collection version defaults to null`() {
        val collection = createCollection()
        assertEquals(null, collection.version)
    }

    // --- CollectionLanguageVariant isPublished ---

    private fun createVariant(workflowStateId: String = "draft") = CollectionLanguageVariant(
        id = UUID.random(),
        languageTag = "es",
        name = "Spanish Variant",
        workflowStateId = workflowStateId,
    )

    @Test
    fun `variant isPublished true when state is published`() {
        val variant = createVariant(workflowStateId = "published")
        assertTrue(variant.isPublished)
    }

    @Test
    fun `variant isPublished false when state is draft`() {
        val variant = createVariant(workflowStateId = "draft")
        assertFalse(variant.isPublished)
    }

    @Test
    fun `variant isPublished false when state is advertised`() {
        val variant = createVariant(workflowStateId = "advertised")
        assertFalse(variant.isPublished)
    }

    @Test
    fun `variant isPublished false when state is review`() {
        val variant = createVariant(workflowStateId = "review")
        assertFalse(variant.isPublished)
    }

    @Test
    fun `variant isPublished false when state is pending`() {
        val variant = createVariant(workflowStateId = "pending")
        assertFalse(variant.isPublished)
    }

    // --- CollectionLanguageVariant isAdvertised ---

    @Test
    fun `variant isAdvertised true when state is advertised`() {
        val variant = createVariant(workflowStateId = "advertised")
        assertTrue(variant.isAdvertised)
    }

    @Test
    fun `variant isAdvertised false when state is published`() {
        val variant = createVariant(workflowStateId = "published")
        assertFalse(variant.isAdvertised)
    }

    @Test
    fun `variant isAdvertised false when state is draft`() {
        val variant = createVariant(workflowStateId = "draft")
        assertFalse(variant.isAdvertised)
    }

    // --- CollectionLanguageVariant and Collection state consistency ---

    @Test
    fun `variant and collection isPublished behave identically for same state`() {
        val states = listOf("draft", "review", "advertised", "published", "pending")
        for (state in states) {
            val collection = createCollection(workflowStateId = state)
            val variant = createVariant(workflowStateId = state)
            assertEquals(collection.isPublished, variant.isPublished, "isPublished mismatch for state: $state")
            assertEquals(collection.isAdvertised, variant.isAdvertised, "isAdvertised mismatch for state: $state")
        }
    }
}

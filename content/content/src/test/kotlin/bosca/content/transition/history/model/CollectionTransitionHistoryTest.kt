package bosca.content.transition.history.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionTransitionHistoryTest {

    @Test
    fun `CollectionTransitionHistory without languageTag defaults to null`() {
        val history = CollectionTransitionHistory(
            collectionId = UUID.random(),
            languageTag = null,
            fromStateId = "draft",
            toStateId = "published",
            principal = null,
            status = "completed",
            success = true,
            complete = true,
        )
        assertNull(history.languageTag)
    }

    @Test
    fun `CollectionTransitionHistory carries languageTag`() {
        val collectionId = UUID.random()
        val principalId = UUID.random()
        val history = CollectionTransitionHistory(
            collectionId = collectionId,
            languageTag = "es",
            fromStateId = "draft",
            toStateId = "review",
            principal = principalId,
            status = "pending",
            success = true,
            complete = false,
        )
        assertEquals(collectionId, history.collectionId)
        assertEquals("es", history.languageTag)
        assertEquals("draft", history.fromStateId)
        assertEquals("review", history.toStateId)
        assertEquals(principalId, history.principal)
        assertEquals("pending", history.status)
        assertEquals(true, history.success)
        assertEquals(false, history.complete)
    }

    @Test
    fun `CollectionTransitionHistory preserves all fields with languageTag`() {
        val collectionId = UUID.random()
        val principalId = UUID.random()
        val history = CollectionTransitionHistory(
            collectionId = collectionId,
            languageTag = "fr",
            fromStateId = "review",
            toStateId = "published",
            principal = principalId,
            status = "success",
            success = true,
            complete = true,
        )
        assertEquals(collectionId, history.collectionId)
        assertEquals("fr", history.languageTag)
        assertEquals("review", history.fromStateId)
        assertEquals("published", history.toStateId)
        assertEquals(principalId, history.principal)
        assertEquals("success", history.status)
        assertEquals(true, history.success)
        assertEquals(true, history.complete)
    }

    @Test
    fun `CollectionTransitionHistory with null languageTag for collection transitions`() {
        val history = CollectionTransitionHistory(
            collectionId = UUID.random(),
            languageTag = null,
            fromStateId = "draft",
            toStateId = "published",
            principal = UUID.random(),
            status = "failed",
            success = false,
            complete = true,
        )
        assertNull(history.languageTag)
        assertEquals(false, history.success)
        assertEquals(true, history.complete)
    }
}

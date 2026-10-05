package bosca.content.transition.history.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CollectionTransitionHistoryTest {

    @Test
    fun `stores all fields`() {
        val collectionId = Uuid.random()
        val principal = Uuid.random()

        val history = CollectionTransitionHistory(
            collectionId = collectionId,
            languageTag = "en-US",
            fromStateId = "draft",
            toStateId = "published",
            principal = principal,
            status = "completed",
            success = true,
            complete = true
        )

        assertEquals(collectionId, history.collectionId)
        assertEquals("en-US", history.languageTag)
        assertEquals("draft", history.fromStateId)
        assertEquals("published", history.toStateId)
        assertEquals(principal, history.principal)
        assertEquals("completed", history.status)
        assertTrue(history.success)
        assertTrue(history.complete)
    }

    @Test
    fun `nullable fields`() {
        val history = CollectionTransitionHistory(
            collectionId = Uuid.random(),
            languageTag = null,
            fromStateId = "draft",
            toStateId = "review",
            principal = null,
            status = "pending",
            success = false,
            complete = false
        )

        assertNull(history.languageTag)
        assertNull(history.principal)
        assertFalse(history.success)
        assertFalse(history.complete)
    }

    @Test
    fun `data class equality`() {
        val id = Uuid.random()
        val a = CollectionTransitionHistory(collectionId = id, languageTag = null, fromStateId = "d", toStateId = "p", principal = null, status = "s", success = true, complete = true)
        val b = CollectionTransitionHistory(collectionId = id, languageTag = null, fromStateId = "d", toStateId = "p", principal = null, status = "s", success = true, complete = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality on different state`() {
        val id = Uuid.random()
        val a = CollectionTransitionHistory(collectionId = id, languageTag = null, fromStateId = "draft", toStateId = "review", principal = null, status = "s", success = true, complete = true)
        val b = CollectionTransitionHistory(collectionId = id, languageTag = null, fromStateId = "draft", toStateId = "published", principal = null, status = "s", success = true, complete = true)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies fields`() {
        val original = CollectionTransitionHistory(
            collectionId = Uuid.random(),
            languageTag = "en",
            fromStateId = "draft",
            toStateId = "review",
            principal = null,
            status = "pending",
            success = false,
            complete = false
        )
        val copied = original.copy(status = "completed", success = true, complete = true)
        assertEquals("completed", copied.status)
        assertTrue(copied.success)
        assertTrue(copied.complete)
        assertEquals("en", copied.languageTag)
    }
}

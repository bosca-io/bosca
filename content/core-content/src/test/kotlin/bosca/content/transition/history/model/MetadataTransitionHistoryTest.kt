package bosca.content.transition.history.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MetadataTransitionHistoryTest {

    @Test
    fun `stores all fields`() {
        val metadataId = Uuid.random()
        val principal = Uuid.random()

        val history = MetadataTransitionHistory(
            metadataId = metadataId,
            fromStateId = "draft",
            toStateId = "published",
            principal = principal,
            status = "completed",
            success = true,
            complete = true
        )

        assertEquals(metadataId, history.metadataId)
        assertEquals("draft", history.fromStateId)
        assertEquals("published", history.toStateId)
        assertEquals(principal, history.principal)
        assertEquals("completed", history.status)
        assertTrue(history.success)
        assertTrue(history.complete)
    }

    @Test
    fun `nullable principal`() {
        val history = MetadataTransitionHistory(
            metadataId = Uuid.random(),
            fromStateId = "draft",
            toStateId = "review",
            principal = null,
            status = "pending",
            success = false,
            complete = false
        )

        assertNull(history.principal)
        assertFalse(history.success)
        assertFalse(history.complete)
    }

    @Test
    fun `data class equality`() {
        val id = Uuid.random()
        val a = MetadataTransitionHistory(metadataId = id, fromStateId = "d", toStateId = "p", principal = null, status = "s", success = true, complete = true)
        val b = MetadataTransitionHistory(metadataId = id, fromStateId = "d", toStateId = "p", principal = null, status = "s", success = true, complete = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality on different toStateId`() {
        val id = Uuid.random()
        val a = MetadataTransitionHistory(metadataId = id, fromStateId = "draft", toStateId = "review", principal = null, status = "s", success = true, complete = true)
        val b = MetadataTransitionHistory(metadataId = id, fromStateId = "draft", toStateId = "published", principal = null, status = "s", success = true, complete = true)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies fields`() {
        val original = MetadataTransitionHistory(
            metadataId = Uuid.random(),
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
    }
}

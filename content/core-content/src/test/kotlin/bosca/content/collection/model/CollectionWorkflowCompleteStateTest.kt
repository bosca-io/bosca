package bosca.content.collection.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class CollectionWorkflowCompleteStateTest {

    @Test
    fun `stores all fields`() {
        val id = Uuid.random()
        val state = CollectionWorkflowCompleteState(
            collectionId = id,
            status = "completed"
        )
        assertEquals(id, state.collectionId)
        assertEquals("completed", state.status)
    }

    @Test
    fun `data class equality`() {
        val id = Uuid.random()
        val a = CollectionWorkflowCompleteState(collectionId = id, status = "done")
        val b = CollectionWorkflowCompleteState(collectionId = id, status = "done")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality on different status`() {
        val id = Uuid.random()
        val a = CollectionWorkflowCompleteState(collectionId = id, status = "done")
        val b = CollectionWorkflowCompleteState(collectionId = id, status = "pending")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies status`() {
        val id = Uuid.random()
        val original = CollectionWorkflowCompleteState(collectionId = id, status = "pending")
        val copied = original.copy(status = "complete")
        assertEquals("complete", copied.status)
        assertEquals(id, copied.collectionId)
    }
}

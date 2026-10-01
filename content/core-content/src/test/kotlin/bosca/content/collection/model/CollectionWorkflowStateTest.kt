package bosca.content.collection.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class CollectionWorkflowStateTest {

    @Test
    fun `CollectionWorkflowState stores all properties`() {
        val id = Uuid.random()
        val state = CollectionWorkflowState(
            collectionId = id,
            stateId = "published",
            status = "active",
            immediate = true
        )
        assertEquals(id, state.collectionId)
        assertEquals("published", state.stateId)
        assertEquals("active", state.status)
        assertEquals(true, state.immediate)
    }

    @Test
    fun `CollectionWorkflowCompleteState stores properties`() {
        val id = Uuid.random()
        val state = CollectionWorkflowCompleteState(
            collectionId = id,
            status = "completed"
        )
        assertEquals(id, state.collectionId)
        assertEquals("completed", state.status)
    }
}

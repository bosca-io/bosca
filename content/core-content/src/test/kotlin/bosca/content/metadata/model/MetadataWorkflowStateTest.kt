package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MetadataWorkflowStateTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun fieldsArePreserved() {
        val state = MetadataWorkflowState(
            metadataId = testId,
            stateId = "review",
            status = "pending",
            immediate = true
        )
        assertEquals(testId, state.metadataId)
        assertEquals("review", state.stateId)
        assertEquals("pending", state.status)
        assertTrue(state.immediate)
    }

    @Test
    fun dataClassEquality() {
        val a = MetadataWorkflowState(metadataId = testId, stateId = "s1", status = "active", immediate = false)
        val b = MetadataWorkflowState(metadataId = testId, stateId = "s1", status = "active", immediate = false)
        assertEquals(a, b)
    }

    @Test
    fun immediateCanBeFalse() {
        val state = MetadataWorkflowState(
            metadataId = testId,
            stateId = "draft",
            status = "new",
            immediate = false
        )
        assertFalse(state.immediate)
    }
}

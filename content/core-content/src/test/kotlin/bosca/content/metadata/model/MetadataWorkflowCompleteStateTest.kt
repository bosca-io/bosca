package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class MetadataWorkflowCompleteStateTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun fieldsArePreserved() {
        val state = MetadataWorkflowCompleteState(metadataId = testId, status = "completed")
        assertEquals(testId, state.metadataId)
        assertEquals("completed", state.status)
    }

    @Test
    fun dataClassEquality() {
        val a = MetadataWorkflowCompleteState(metadataId = testId, status = "done")
        val b = MetadataWorkflowCompleteState(metadataId = testId, status = "done")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}

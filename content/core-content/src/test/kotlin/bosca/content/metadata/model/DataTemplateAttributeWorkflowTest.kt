package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DataTemplateAttributeWorkflowTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun fieldsArePreserved() {
        val workflow = DataTemplateAttributeWorkflow(
            metadataId = testId,
            version = 2,
            key = "review",
            workflowId = "wf-review-001",
            autoRun = true
        )
        assertEquals(testId, workflow.metadataId)
        assertEquals(2, workflow.version)
        assertEquals("review", workflow.key)
        assertEquals("wf-review-001", workflow.workflowId)
        assertTrue(workflow.autoRun)
    }

    @Test
    fun autoRunDefaultsFalse() {
        val workflow = DataTemplateAttributeWorkflow(
            metadataId = testId,
            version = 1,
            key = "k",
            workflowId = "wf"
        )
        assertFalse(workflow.autoRun)
    }
}

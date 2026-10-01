package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DocumentTemplateContainerWorkflowTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun fieldsArePreserved() {
        val workflow = DocumentTemplateContainerWorkflow(
            metadataId = testId,
            version = 2,
            id = "container-1",
            name = "Review Workflow",
            workflowId = "wf-review",
            autoRun = true
        )
        assertEquals(testId, workflow.metadataId)
        assertEquals(2, workflow.version)
        assertEquals("container-1", workflow.id)
        assertEquals("Review Workflow", workflow.name)
        assertEquals("wf-review", workflow.workflowId)
        assertTrue(workflow.autoRun)
    }

    @Test
    fun autoRunDefaultsFalse() {
        val workflow = DocumentTemplateContainerWorkflow(
            metadataId = testId,
            version = 1,
            id = "c1",
            name = "n",
            workflowId = "wf"
        )
        assertFalse(workflow.autoRun)
    }
}

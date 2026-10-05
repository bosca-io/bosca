package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DocumentTemplateAttributeWorkflowTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `stores all required fields`() {
        val workflow = DocumentTemplateAttributeWorkflow(
            metadataId = testId,
            version = 2,
            key = "title",
            workflowId = "wf-publish"
        )
        assertEquals(testId, workflow.metadataId)
        assertEquals(2, workflow.version)
        assertEquals("title", workflow.key)
        assertEquals("wf-publish", workflow.workflowId)
    }

    @Test
    fun `autoRun defaults to false`() {
        val workflow = DocumentTemplateAttributeWorkflow(
            metadataId = testId,
            version = 1,
            key = "body",
            workflowId = "wf-1"
        )
        assertFalse(workflow.autoRun)
    }

    @Test
    fun `autoRun can be set to true`() {
        val workflow = DocumentTemplateAttributeWorkflow(
            metadataId = testId,
            version = 1,
            key = "body",
            workflowId = "wf-1",
            autoRun = true
        )
        assertTrue(workflow.autoRun)
    }
}

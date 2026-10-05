package bosca.content.attributes.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateWorkflowTest {

    @Test
    fun `stores workflowId and autoRun`() {
        val wf = TemplateWorkflow(workflowId = "wf-publish", autoRun = true)
        assertEquals("wf-publish", wf.workflowId)
        assertTrue(wf.autoRun)
    }

    @Test
    fun `autoRun false`() {
        val wf = TemplateWorkflow(workflowId = "wf-review", autoRun = false)
        assertEquals("wf-review", wf.workflowId)
        assertFalse(wf.autoRun)
    }
}

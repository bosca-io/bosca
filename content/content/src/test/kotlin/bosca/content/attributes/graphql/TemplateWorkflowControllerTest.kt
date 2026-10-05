package bosca.content.attributes.graphql

import bosca.content.attributes.model.TemplateWorkflow
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateWorkflowControllerTest {

    private val controller = TemplateWorkflowController()

    @Test
    fun `autoRun returns true when set`() {
        val workflow = TemplateWorkflow(workflowId = "wf-1", autoRun = true)

        assertEquals(true, controller.autoRun(workflow))
    }

    @Test
    fun `autoRun returns false when set`() {
        val workflow = TemplateWorkflow(workflowId = "wf-1", autoRun = false)

        assertEquals(false, controller.autoRun(workflow))
    }
}

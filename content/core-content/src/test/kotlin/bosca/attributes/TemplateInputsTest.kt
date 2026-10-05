package bosca.attributes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateInputsTest {

    // --- TemplateAttributeInput ---

    @Test
    fun `TemplateAttributeInput optional fields have correct defaults`() {
        val input = TemplateAttributeInput(
            key = "k", name = "n", description = "d",
            type = AttributeType.STRING, ui = AttributeUiType.INPUT
        )
        assertNull(input.supplementaryKey)
        assertNull(input.configuration)
        assertFalse(input.list)
        assertEquals(AttributeLocation.ITEM, input.location)
        assertNull(input.workflows)
        assertNull(input.tools)
    }

    @Test
    fun `TemplateAttributeInput stores all properties`() {
        val workflow = TemplateWorkflowInput(workflowId = "wf-1", autoRun = true)
        val tool = TemplateToolInput(name = "tool-1")
        val input = TemplateAttributeInput(
            key = "title", name = "Title", description = "The title",
            supplementaryKey = "sup", type = AttributeType.STRING,
            ui = AttributeUiType.TEXTAREA, list = true,
            location = AttributeLocation.RELATIONSHIP,
            workflows = listOf(workflow), tools = listOf(tool)
        )
        assertEquals("title", input.key)
        assertEquals("sup", input.supplementaryKey)
        assertTrue(input.list)
        assertEquals(AttributeLocation.RELATIONSHIP, input.location)
        assertEquals(1, input.workflows!!.size)
        assertEquals(1, input.tools!!.size)
    }

    // --- TemplateToolInput ---

    @Test
    fun `TemplateToolInput all fields default to null`() {
        val input = TemplateToolInput()
        assertNull(input.id)
        assertNull(input.name)
        assertNull(input.description)
        assertNull(input.query)
        assertNull(input.resultPath)
    }

    @Test
    fun `TemplateToolInput stores properties`() {
        val input = TemplateToolInput(name = "Search", description = "Search tool", query = "SELECT 1", resultPath = "$.data")
        assertEquals("Search", input.name)
        assertEquals("Search tool", input.description)
        assertEquals("SELECT 1", input.query)
        assertEquals("$.data", input.resultPath)
    }

    // --- TemplateWorkflowInput ---

    @Test
    fun `TemplateWorkflowInput stores workflowId and autoRun`() {
        val input = TemplateWorkflowInput(workflowId = "wf-123", autoRun = false)
        assertEquals("wf-123", input.workflowId)
        assertFalse(input.autoRun)
    }

    @Test
    fun `TemplateWorkflowInput autoRun can be true`() {
        val input = TemplateWorkflowInput(workflowId = "wf-1", autoRun = true)
        assertTrue(input.autoRun)
    }
}

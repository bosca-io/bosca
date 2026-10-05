package bosca.content.attributes.graphql

import bosca.content.attributes.model.TemplateTool
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemplateToolControllerTest {

    private val controller = TemplateToolController()

    @Test
    fun `id returns tool id`() {
        val toolId = UUID.random()
        val tool = TemplateTool(id = toolId, name = "Tool", query = "query { tool }")

        assertEquals(toolId, controller.id(tool))
    }

    @Test
    fun `name returns tool name`() {
        val tool = TemplateTool(name = "My Tool", query = "query { tool }")

        assertEquals("My Tool", controller.name(tool))
    }

    @Test
    fun `description returns tool description`() {
        val tool = TemplateTool(name = "Tool", description = "A useful tool", query = "query { tool }")

        assertEquals("A useful tool", controller.description(tool))
    }

    @Test
    fun `description returns null when not set`() {
        val tool = TemplateTool(name = "Tool", query = "query { tool }")

        assertNull(controller.description(tool))
    }

    @Test
    fun `query returns tool query`() {
        val tool = TemplateTool(name = "Tool", query = "query { metadata { id } }")

        assertEquals("query { metadata { id } }", controller.query(tool))
    }

    @Test
    fun `resultPath returns tool resultPath`() {
        val tool = TemplateTool(name = "Tool", query = "q", resultPath = "data.metadata")

        assertEquals("data.metadata", controller.resultPath(tool))
    }

    @Test
    fun `resultPath returns null when not set`() {
        val tool = TemplateTool(name = "Tool", query = "q")

        assertNull(controller.resultPath(tool))
    }
}

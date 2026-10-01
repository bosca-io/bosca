package bosca.content.tools.graphql

import bosca.content.tools.model.TemplateAttributeTool
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemplateAttributeToolControllerCoverageTest {

    private val controller = TemplateAttributeToolController()

    @Test
    fun `id returns id from tool`() {
        val id = UUID.random()
        val tool = TemplateAttributeTool(
            id = id,
            key = "key",
            name = "name",
            query = "query"
        )

        assertEquals(id, controller.id(tool))
    }

    @Test
    fun `key returns key from tool`() {
        val tool = TemplateAttributeTool(
            id = UUID.random(),
            key = "the-key",
            name = "name",
            query = "query"
        )

        assertEquals("the-key", controller.key(tool))
    }

    @Test
    fun `name returns name from tool`() {
        val tool = TemplateAttributeTool(
            id = UUID.random(),
            key = "key",
            name = "the-name",
            query = "query"
        )

        assertEquals("the-name", controller.name(tool))
    }

    @Test
    fun `description returns description from tool`() {
        val tool = TemplateAttributeTool(
            id = UUID.random(),
            key = "key",
            name = "name",
            description = "the-description",
            query = "query"
        )

        assertEquals("the-description", controller.description(tool))
    }

    @Test
    fun `description returns null when description is null`() {
        val tool = TemplateAttributeTool(
            id = UUID.random(),
            key = "key",
            name = "name",
            description = null,
            query = "query"
        )

        assertNull(controller.description(tool))
    }

    @Test
    fun `query returns query from tool`() {
        val tool = TemplateAttributeTool(
            id = UUID.random(),
            key = "key",
            name = "name",
            query = "the-query"
        )

        assertEquals("the-query", controller.query(tool))
    }

    @Test
    fun `resultPath returns result path from tool`() {
        val tool = TemplateAttributeTool(
            id = UUID.random(),
            key = "key",
            name = "name",
            query = "query",
            resultPath = "the-result-path"
        )

        assertEquals("the-result-path", controller.resultPath(tool))
    }

    @Test
    fun `resultPath returns null when result path is null`() {
        val tool = TemplateAttributeTool(
            id = UUID.random(),
            key = "key",
            name = "name",
            query = "query",
            resultPath = null
        )

        assertNull(controller.resultPath(tool))
    }
}

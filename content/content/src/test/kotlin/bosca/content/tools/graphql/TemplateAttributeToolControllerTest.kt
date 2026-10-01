package bosca.content.tools.graphql

import bosca.content.tools.model.TemplateAttributeTool
import bosca.serialization.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateAttributeToolControllerTest {

    private val controller = TemplateAttributeToolController()

    @Test
    fun `configuration returns configuration from tool`() {
        val config = buildJsonObject {
            put("key", "value")
        }
        val tool = TemplateAttributeTool(
            id = UUID.random(),
            key = "key",
            name = "name",
            query = "query",
            configuration = config
        )

        val result = controller.configuration(tool)

        assertEquals(config, result)
    }

    @Test
    fun `configuration returns null when configuration is null`() {
        val tool = TemplateAttributeTool(
            id = UUID.random(),
            key = "key",
            name = "name",
            query = "query",
            configuration = null
        )

        val result = controller.configuration(tool)

        assertEquals(null, result)
    }
}

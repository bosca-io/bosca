package bosca.content.metadata.model

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.attributes.TemplateAttributeInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DocumentTemplateInputTest {

    @Test
    fun `DocumentTemplateInput defaults`() {
        val input = DocumentTemplateInput()
        assertNull(input.configuration)
        assertNull(input.schema)
        assertNull(input.content)
        assertNull(input.defaultAttributes)
        assertEquals(emptyList(), input.attributes)
        assertEquals(emptyList(), input.containers)
    }

    @Test
    fun `DocumentTemplateInput stores all properties`() {
        val config = buildJsonObject { put("key", "val") }
        val schema = buildJsonObject { put("type", "object") }
        val defaultAttrs = buildJsonObject { put("default", true) }
        val attr = TemplateAttributeInput(
            key = "title", name = "Title", description = "d",
            type = AttributeType.STRING, ui = AttributeUiType.INPUT
        )
        val container = DocumentTemplateContainerInput(
            id = "c1", name = "Container", description = "desc"
        )

        val input = DocumentTemplateInput(
            configuration = config,
            schema = schema,
            defaultAttributes = defaultAttrs,
            attributes = listOf(attr),
            containers = listOf(container)
        )

        assertEquals(config, input.configuration)
        assertEquals(schema, input.schema)
        assertEquals(defaultAttrs, input.defaultAttributes)
        assertEquals(1, input.attributes.size)
        assertEquals("title", input.attributes[0].key)
        assertEquals(1, input.containers.size)
        assertEquals("c1", input.containers[0].id)
    }
}

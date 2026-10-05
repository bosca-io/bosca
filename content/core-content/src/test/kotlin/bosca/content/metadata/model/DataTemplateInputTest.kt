package bosca.content.metadata.model

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.attributes.TemplateAttributeInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DataTemplateInputTest {

    @Test
    fun `DataTemplateInput defaults`() {
        val input = DataTemplateInput()
        assertNull(input.type)
        assertNull(input.defaultAttributes)
        assertEquals(emptyList(), input.attributes)
    }

    @Test
    fun `DataTemplateInput stores all properties`() {
        val defaultAttrs = buildJsonObject { put("key", "value") }
        val attr = TemplateAttributeInput(
            key = "field", name = "Field", description = "desc",
            type = AttributeType.INT, ui = AttributeUiType.INPUT
        )
        val input = DataTemplateInput(
            type = DataType.TABLE,
            defaultAttributes = defaultAttrs,
            attributes = listOf(attr)
        )
        assertEquals(DataType.TABLE, input.type)
        assertEquals(defaultAttrs, input.defaultAttributes)
        assertEquals(1, input.attributes.size)
        assertEquals("field", input.attributes[0].key)
    }

    @Test
    fun `DataTemplateInput data class equality`() {
        val input1 = DataTemplateInput(type = DataType.ATTRIBUTES)
        val input2 = DataTemplateInput(type = DataType.ATTRIBUTES)
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `DataTemplateInput copy preserves unchanged fields`() {
        val attrs = buildJsonObject { put("d", 1) }
        val input = DataTemplateInput(type = DataType.TABLE, defaultAttributes = attrs)
        val copied = input.copy(type = DataType.ATTRIBUTES)
        assertEquals(DataType.ATTRIBUTES, copied.type)
        assertEquals(attrs, copied.defaultAttributes)
    }
}

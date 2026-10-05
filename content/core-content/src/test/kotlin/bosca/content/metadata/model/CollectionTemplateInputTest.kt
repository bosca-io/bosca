package bosca.content.metadata.model

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.attributes.TemplateAttributeInput
import bosca.content.ordering.OrderingInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CollectionTemplateInputTest {

    @Test
    fun `CollectionTemplateInput defaults`() {
        val input = CollectionTemplateInput()
        assertEquals(emptyList(), input.attributes)
        assertNull(input.defaultAttributes)
        assertNull(input.filters)
        assertEquals(emptyList(), input.ordering)
        assertNull(input.configuration)
    }

    @Test
    fun `CollectionTemplateInput stores all properties`() {
        val attr = TemplateAttributeInput(
            key = "title", name = "Title", description = "d",
            type = AttributeType.STRING, ui = AttributeUiType.INPUT
        )
        val defaultAttrs = buildJsonObject { put("default", "val") }
        val config = buildJsonObject { put("setting", true) }
        val ordering = listOf(OrderingInput(field = "name"))
        val filters = CollectionTemplateFilters(
            filters = listOf(CollectionTemplateFilter(filter = "f", name = "n"))
        )

        val input = CollectionTemplateInput(
            attributes = listOf(attr),
            defaultAttributes = defaultAttrs,
            filters = filters,
            ordering = ordering,
            configuration = config
        )

        assertEquals(1, input.attributes.size)
        assertEquals("title", input.attributes[0].key)
        assertEquals(defaultAttrs, input.defaultAttributes)
        assertEquals(1, input.filters!!.filters.size)
        assertEquals(1, input.ordering.size)
        assertEquals(config, input.configuration)
    }

    @Test
    fun `CollectionTemplateInput data class equality`() {
        val input1 = CollectionTemplateInput()
        val input2 = CollectionTemplateInput()
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `CollectionTemplateInput copy preserves unchanged fields`() {
        val config = buildJsonObject { put("x", 1) }
        val input = CollectionTemplateInput(configuration = config)
        val copied = input.copy(ordering = listOf(OrderingInput(field = "date")))
        assertEquals(config, copied.configuration)
        assertEquals(1, copied.ordering.size)
    }
}

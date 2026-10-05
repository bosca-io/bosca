package bosca.content.tools.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TemplateAttributeToolInputTest {

    @Test
    fun `TemplateAttributeToolInput stores required fields`() {
        val input = TemplateAttributeToolInput(
            key = "search-tool",
            name = "Search",
            query = "SELECT * FROM items"
        )
        assertEquals("search-tool", input.key)
        assertEquals("Search", input.name)
        assertEquals("SELECT * FROM items", input.query)
    }

    @Test
    fun `TemplateAttributeToolInput optional fields default to null`() {
        val input = TemplateAttributeToolInput(
            key = "k", name = "n", query = "q"
        )
        assertNull(input.description)
        assertNull(input.resultPath)
        assertNull(input.configuration)
    }

    @Test
    fun `TemplateAttributeToolInput stores all properties`() {
        val config = buildJsonObject { put("timeout", 30) }
        val input = TemplateAttributeToolInput(
            key = "tool-1",
            name = "Data Fetcher",
            description = "Fetches data from external source",
            query = "SELECT id, name FROM items WHERE active = true",
            resultPath = "$.data.items",
            configuration = config
        )

        assertEquals("tool-1", input.key)
        assertEquals("Data Fetcher", input.name)
        assertEquals("Fetches data from external source", input.description)
        assertEquals("SELECT id, name FROM items WHERE active = true", input.query)
        assertEquals("$.data.items", input.resultPath)
        assertEquals(config, input.configuration)
    }

    @Test
    fun `TemplateAttributeToolInput data class equality`() {
        val input1 = TemplateAttributeToolInput(key = "k", name = "n", query = "q")
        val input2 = TemplateAttributeToolInput(key = "k", name = "n", query = "q")
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `TemplateAttributeToolInput inequality on different key`() {
        val input1 = TemplateAttributeToolInput(key = "k1", name = "n", query = "q")
        val input2 = TemplateAttributeToolInput(key = "k2", name = "n", query = "q")
        assertNotEquals(input1, input2)
    }

    @Test
    fun `TemplateAttributeToolInput copy preserves unchanged fields`() {
        val config = buildJsonObject { put("k", "v") }
        val input = TemplateAttributeToolInput(
            key = "tool", name = "Tool", query = "SELECT 1",
            description = "desc", resultPath = "$.data",
            configuration = config
        )
        val copied = input.copy(name = "Updated Tool")
        assertEquals("Updated Tool", copied.name)
        assertEquals("tool", copied.key)
        assertEquals("SELECT 1", copied.query)
        assertEquals("desc", copied.description)
        assertEquals("$.data", copied.resultPath)
        assertEquals(config, copied.configuration)
    }
}

package bosca.content.tools.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class TemplateAttributeToolTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `stores all required fields`() {
        val tool = TemplateAttributeTool(
            id = testId,
            key = "lookup",
            name = "Lookup Tool",
            query = "SELECT * FROM items"
        )
        assertEquals(testId, tool.id)
        assertEquals("lookup", tool.key)
        assertEquals("Lookup Tool", tool.name)
        assertEquals("SELECT * FROM items", tool.query)
    }

    @Test
    fun `optional fields default to null`() {
        val tool = TemplateAttributeTool(
            id = testId,
            key = "k",
            name = "n",
            query = "q"
        )
        assertNull(tool.description)
        assertNull(tool.resultPath)
        assertNull(tool.configuration)
    }

    @Test
    fun `stores optional fields when provided`() {
        val config = JsonObject(mapOf("timeout" to JsonPrimitive(30)))
        val tool = TemplateAttributeTool(
            id = testId,
            key = "k",
            name = "n",
            description = "A tool description",
            query = "q",
            resultPath = "data.items",
            configuration = config
        )
        assertEquals("A tool description", tool.description)
        assertEquals("data.items", tool.resultPath)
        assertEquals(config, tool.configuration)
    }

    @Test
    fun `data class equality`() {
        val a = TemplateAttributeTool(id = testId, key = "k", name = "n", query = "q")
        val b = TemplateAttributeTool(id = testId, key = "k", name = "n", query = "q")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality on different key`() {
        val a = TemplateAttributeTool(id = testId, key = "k1", name = "n", query = "q")
        val b = TemplateAttributeTool(id = testId, key = "k2", name = "n", query = "q")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies fields`() {
        val original = TemplateAttributeTool(id = testId, key = "k", name = "n", query = "q")
        val copied = original.copy(name = "Updated", description = "desc")
        assertEquals("Updated", copied.name)
        assertEquals("desc", copied.description)
        assertEquals("k", copied.key)
    }
}

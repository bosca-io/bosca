package bosca.content.attributes.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class TemplateToolTest {

    @Test
    fun `all fields default to null`() {
        val tool = TemplateTool()
        assertNull(tool.id)
        assertNull(tool.name)
        assertNull(tool.description)
        assertNull(tool.query)
        assertNull(tool.resultPath)
    }

    @Test
    fun `stores all fields when provided`() {
        val id = Uuid.random()
        val tool = TemplateTool(
            id = id,
            name = "Lookup",
            description = "Performs a lookup",
            query = "SELECT * FROM items",
            resultPath = "data.result"
        )
        assertEquals(id, tool.id)
        assertEquals("Lookup", tool.name)
        assertEquals("Performs a lookup", tool.description)
        assertEquals("SELECT * FROM items", tool.query)
        assertEquals("data.result", tool.resultPath)
    }

    @Test
    fun `data class equality`() {
        val id = Uuid.random()
        val a = TemplateTool(id = id, name = "n", description = "d", query = "q", resultPath = "r")
        val b = TemplateTool(id = id, name = "n", description = "d", query = "q", resultPath = "r")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality`() {
        val a = TemplateTool(name = "a")
        val b = TemplateTool(name = "b")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies fields`() {
        val original = TemplateTool(name = "original")
        val copied = original.copy(name = "updated", query = "SELECT 1")
        assertEquals("updated", copied.name)
        assertEquals("SELECT 1", copied.query)
        assertNull(copied.id)
    }
}

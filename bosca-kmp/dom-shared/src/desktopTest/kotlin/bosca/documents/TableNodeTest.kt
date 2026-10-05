package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableNodeTest {

    @Test
    fun `TableNode default creation has empty content`() {
        val node = TableNode()
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `TableNode default creation has empty marks`() {
        val node = TableNode()
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `TableNode default attributes has null classes`() {
        val node = TableNode()
        assertNull(node.attributes.classes)
    }

    @Test
    fun `TableNode preserves content`() {
        val child = TableRowNode()
        val node = TableNode(content = listOf(child))
        assertEquals(1, node.content.size)
    }

    @Test
    fun `TableNode preserves marks`() {
        val node = TableNode(marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }

    @Test
    fun `TableNodeAttributes withClasses creates copy with new classes`() {
        val original = TableNodeAttributes()
        val modified = original.withClasses("table-class")
        assertEquals("table-class", modified.classes)
        assertNull(original.classes)
    }

    @Test
    fun `TableNode preserves attributes with classes`() {
        val node = TableNode(attributes = TableNodeAttributes(classes = "styled"))
        assertEquals("styled", node.attributes.classes)
    }
}

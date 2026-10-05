package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableRowNodeTest {

    @Test
    fun `TableRowNode default creation has empty content`() {
        val node = TableRowNode()
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `TableRowNode default creation has empty marks`() {
        val node = TableRowNode()
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `TableRowNode default attributes has null classes`() {
        val node = TableRowNode()
        assertNull(node.attributes.classes)
    }

    @Test
    fun `TableRowNode preserves content`() {
        val child = TableCellNode()
        val node = TableRowNode(content = listOf(child))
        assertEquals(1, node.content.size)
    }

    @Test
    fun `TableRowNode preserves marks`() {
        val node = TableRowNode(marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }

    @Test
    fun `TableRowNodeAttributes withClasses creates copy with new classes`() {
        val original = TableRowNodeAttributes()
        val modified = original.withClasses("row-class")
        assertEquals("row-class", modified.classes)
        assertNull(original.classes)
    }

    @Test
    fun `TableRowNode preserves attributes with classes`() {
        val node = TableRowNode(attributes = TableRowNodeAttributes(classes = "styled"))
        assertEquals("styled", node.attributes.classes)
    }
}

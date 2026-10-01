package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableCellNodeTest {

    @Test
    fun `TableCellNode default creation has empty content`() {
        val node = TableCellNode()
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `TableCellNode default creation has empty marks`() {
        val node = TableCellNode()
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `TableCellNode default attributes has colspan 1`() {
        val node = TableCellNode()
        assertEquals(1, node.attributes.colspan)
    }

    @Test
    fun `TableCellNode default attributes has rowspan 1`() {
        val node = TableCellNode()
        assertEquals(1, node.attributes.rowspan)
    }

    @Test
    fun `TableCellNode default attributes has null classes`() {
        val node = TableCellNode()
        assertNull(node.attributes.classes)
    }

    @Test
    fun `TableCellNode preserves colspan and rowspan`() {
        val attrs = TableCellNodeAttributes(colspan = 2, rowspan = 3)
        val node = TableCellNode(attributes = attrs)
        assertEquals(2, node.attributes.colspan)
        assertEquals(3, node.attributes.rowspan)
    }

    @Test
    fun `TableCellNode preserves content`() {
        val child = ParagraphNode()
        val node = TableCellNode(content = listOf(child))
        assertEquals(1, node.content.size)
    }

    @Test
    fun `TableCellNode preserves marks`() {
        val node = TableCellNode(marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }

    @Test
    fun `TableCellNodeAttributes withClasses creates copy with new classes`() {
        val original = TableCellNodeAttributes(colspan = 2, rowspan = 3)
        val modified = original.withClasses("cell-class")
        assertEquals("cell-class", modified.classes)
        assertEquals(2, modified.colspan)
        assertEquals(3, modified.rowspan)
        assertNull(original.classes)
    }
}

package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderedListNodeTest {

    @Test
    fun `OrderedListNode default creation has empty content`() {
        val node = OrderedListNode()
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `OrderedListNode default creation has empty marks`() {
        val node = OrderedListNode()
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `OrderedListNode default attributes has null classes`() {
        val node = OrderedListNode()
        assertNull(node.attributes.classes)
    }

    @Test
    fun `OrderedListNode default attributes has null start`() {
        val node = OrderedListNode()
        assertNull(node.attributes.start)
    }

    @Test
    fun `OrderedListNode preserves start attribute`() {
        val node = OrderedListNode(attributes = OrderedListAttributes(start = 5))
        assertEquals(5, node.attributes.start)
    }

    @Test
    fun `OrderedListNode preserves content`() {
        val child = ListItemNode()
        val node = OrderedListNode(content = listOf(child))
        assertEquals(1, node.content.size)
    }

    @Test
    fun `OrderedListNode preserves marks`() {
        val node = OrderedListNode(marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }

    @Test
    fun `OrderedListAttributes withClasses creates copy with new classes`() {
        val original = OrderedListAttributes(start = 3)
        val modified = original.withClasses("ol-class")
        assertEquals("ol-class", modified.classes)
        assertEquals(3, modified.start)
        assertNull(original.classes)
    }
}

package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListItemNodeTest {

    @Test
    fun `ListItemNode default creation has empty content`() {
        val node = ListItemNode()
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `ListItemNode default creation has empty marks`() {
        val node = ListItemNode()
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `ListItemNode default attributes has null classes`() {
        val node = ListItemNode()
        assertNull(node.attributes.classes)
    }

    @Test
    fun `ListItemNode preserves content`() {
        val child = ParagraphNode()
        val node = ListItemNode(content = listOf(child))
        assertEquals(1, node.content.size)
    }

    @Test
    fun `ListItemNode preserves marks`() {
        val node = ListItemNode(marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }

    @Test
    fun `ListItemAttributes withClasses creates copy with new classes`() {
        val original = ListItemAttributes()
        val modified = original.withClasses("item-class")
        assertEquals("item-class", modified.classes)
        assertNull(original.classes)
    }

    @Test
    fun `ListItemNode preserves attributes with classes`() {
        val node = ListItemNode(attributes = ListItemAttributes(classes = "styled"))
        assertEquals("styled", node.attributes.classes)
    }
}

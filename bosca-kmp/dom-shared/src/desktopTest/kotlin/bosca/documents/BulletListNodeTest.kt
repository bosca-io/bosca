package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BulletListNodeTest {

    @Test
    fun `BulletListNode default creation has empty content`() {
        val node = BulletListNode()
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `BulletListNode default creation has empty marks`() {
        val node = BulletListNode()
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `BulletListNode default attributes has null classes`() {
        val node = BulletListNode()
        assertNull(node.attributes.classes)
    }

    @Test
    fun `BulletListNode preserves content`() {
        val child = ListItemNode()
        val node = BulletListNode(content = listOf(child))
        assertEquals(1, node.content.size)
    }

    @Test
    fun `BulletListNode preserves marks`() {
        val node = BulletListNode(marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }

    @Test
    fun `BulletListAttributes withClasses creates copy with new classes`() {
        val original = BulletListAttributes()
        val modified = original.withClasses("list-class")
        assertEquals("list-class", modified.classes)
        assertNull(original.classes)
    }

    @Test
    fun `BulletListNode preserves attributes with classes`() {
        val node = BulletListNode(attributes = BulletListAttributes(classes = "styled"))
        assertEquals("styled", node.attributes.classes)
    }
}

package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParagraphNodeTest {

    @Test
    fun `ParagraphNode default creation has empty content`() {
        val node = ParagraphNode()
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `ParagraphNode default creation has empty marks`() {
        val node = ParagraphNode()
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `ParagraphNode default attributes has null classes`() {
        val node = ParagraphNode()
        assertNull(node.attributes.classes)
    }

    @Test
    fun `ParagraphNode default attributes has null textAlign`() {
        val node = ParagraphNode()
        assertNull(node.attributes.textAlign)
    }

    @Test
    fun `ParagraphNode preserves textAlign attribute`() {
        val node = ParagraphNode(attributes = ParagraphAttributes(textAlign = "center"))
        assertEquals("center", node.attributes.textAlign)
    }

    @Test
    fun `ParagraphNode preserves content`() {
        val child = TextNode(text = "Hello")
        val node = ParagraphNode(content = listOf(child))
        assertEquals(1, node.content.size)
    }

    @Test
    fun `ParagraphNode preserves marks`() {
        val node = ParagraphNode(marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }

    @Test
    fun `ParagraphAttributes withClasses creates copy with new classes`() {
        val original = ParagraphAttributes(textAlign = "right")
        val modified = original.withClasses("para-class")
        assertEquals("para-class", modified.classes)
        assertEquals("right", modified.textAlign)
        assertNull(original.classes)
    }
}

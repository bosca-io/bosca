package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextNodeTest {

    @Test
    fun `TextNode preserves text field`() {
        val node = TextNode(text = "Hello world")
        assertEquals("Hello world", node.text)
    }

    @Test
    fun `TextNode default content is empty`() {
        val node = TextNode(text = "test")
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `TextNode default marks is empty`() {
        val node = TextNode(text = "test")
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `TextNode default attributes has null classes`() {
        val node = TextNode(text = "test")
        assertNull(node.attributes.classes)
    }

    @Test
    fun `TextNode default attributes has null transform`() {
        val node = TextNode(text = "test")
        assertNull(node.attributes.transform)
    }

    @Test
    fun `TextNode preserves transform attribute`() {
        val node = TextNode(text = "test", attributes = TextAttributes(transform = "uppercase"))
        assertEquals("uppercase", node.attributes.transform)
    }

    @Test
    fun `TextNode preserves marks`() {
        val node = TextNode(text = "bold text", marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }

    @Test
    fun `TextAttributes withClasses creates copy with new classes`() {
        val original = TextAttributes(transform = "lowercase")
        val modified = original.withClasses("text-class")
        assertEquals("text-class", modified.classes)
        assertEquals("lowercase", modified.transform)
        assertNull(original.classes)
    }
}

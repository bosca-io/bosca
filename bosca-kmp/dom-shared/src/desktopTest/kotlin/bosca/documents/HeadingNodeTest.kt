package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadingNodeTest {

    @Test
    fun `HeadingNode stores level in attributes`() {
        val node = HeadingNode(attributes = HeadingAttributes(level = 2))
        assertEquals(2, node.attributes.level)
    }

    @Test
    fun `HeadingNode stores textAlign in attributes`() {
        val node = HeadingNode(attributes = HeadingAttributes(level = 1, textAlign = "center"))
        assertEquals("center", node.attributes.textAlign)
    }

    @Test
    fun `HeadingNode default content is empty`() {
        val node = HeadingNode(attributes = HeadingAttributes(level = 1))
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `HeadingNode default marks is empty`() {
        val node = HeadingNode(attributes = HeadingAttributes(level = 1))
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `HeadingNode preserves content`() {
        val child = TextNode(text = "Title")
        val node = HeadingNode(attributes = HeadingAttributes(level = 1), content = listOf(child))
        assertEquals(1, node.content.size)
    }

    @Test
    fun `HeadingNode preserves marks`() {
        val node = HeadingNode(attributes = HeadingAttributes(level = 1), marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }

    @Test
    fun `HeadingAttributes withClasses creates copy with new classes`() {
        val original = HeadingAttributes(level = 3, textAlign = "left")
        val modified = original.withClasses("heading-class")
        assertEquals("heading-class", modified.classes)
        assertEquals(3, modified.level)
        assertEquals("left", modified.textAlign)
        assertNull(original.classes)
    }

    @Test
    fun `HeadingAttributes default classes and textAlign are null`() {
        val attrs = HeadingAttributes(level = 1)
        assertNull(attrs.classes)
        assertNull(attrs.textAlign)
    }
}

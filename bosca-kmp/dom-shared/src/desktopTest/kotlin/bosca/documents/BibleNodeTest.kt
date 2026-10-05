package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BibleNodeTest {

    @Test
    fun `BibleNode default creation has empty content`() {
        val node = BibleNode()
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `BibleNode default creation has empty marks`() {
        val node = BibleNode()
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `BibleNode default attributes has no classes`() {
        val node = BibleNode()
        assertNull(node.attributes.classes)
    }

    @Test
    fun `BibleNode default attributes has empty references`() {
        val node = BibleNode()
        assertTrue(node.attributes.references.isEmpty())
    }

    @Test
    fun `BibleNode preserves attributes with references`() {
        val refs = listOf("GEN.1.1")
        val attrs = BibleAttributes(classes = "verse", references = refs)
        val node = BibleNode(attributes = attrs)
        assertEquals("verse", node.attributes.classes)
        assertEquals(1, node.attributes.references.size)
        assertEquals("GEN.1.1", node.attributes.references[0])
    }

    @Test
    fun `BibleNode preserves content`() {
        val child = ParagraphNode()
        val node = BibleNode(content = listOf(child))
        assertEquals(1, node.content.size)
    }

    @Test
    fun `BibleNode preserves marks`() {
        val node = BibleNode(marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }

    @Test
    fun `BibleAttributes withClasses creates copy with new classes`() {
        val original = BibleAttributes(references = listOf("GEN.1.1"))
        val modified = original.withClasses("highlight")
        assertEquals("highlight", modified.classes)
        assertEquals(1, modified.references.size)
        assertNull(original.classes)
    }
}

package bosca.documents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentNodeTest {

    // --- Document ---

    @Test
    fun `Document default creation has empty content`() {
        val doc = Document()
        assertTrue(doc.content.isEmpty())
    }

    @Test
    fun `Document default creation has empty marks`() {
        val doc = Document()
        assertTrue(doc.marks.isEmpty())
    }

    @Test
    fun `Document default creation has EmptyDocumentAttributes`() {
        val doc = Document()
        assertTrue(doc.attributes is EmptyDocumentAttributes)
    }

    // --- HeadingNode ---

    @Test
    fun `HeadingNode stores level in attributes`() {
        val heading = HeadingNode(attributes = HeadingAttributes(level = 2))
        assertEquals(2, heading.attributes.level)
    }

    @Test
    fun `HeadingNode stores textAlign in attributes`() {
        val heading = HeadingNode(attributes = HeadingAttributes(level = 1, textAlign = "center"))
        assertEquals("center", heading.attributes.textAlign)
    }

    @Test
    fun `HeadingNode default content is empty`() {
        val heading = HeadingNode(attributes = HeadingAttributes(level = 1))
        assertTrue(heading.content.isEmpty())
    }

    @Test
    fun `HeadingNode default marks is empty`() {
        val heading = HeadingNode(attributes = HeadingAttributes(level = 1))
        assertTrue(heading.marks.isEmpty())
    }

    // --- HeadingAttributes ---

    @Test
    fun `HeadingAttributes withClasses creates copy with new classes`() {
        val original = HeadingAttributes(level = 3, textAlign = "left")
        val modified = original.withClasses("my-class")
        assertEquals("my-class", modified.classes)
        assertEquals(3, modified.level)
        assertEquals("left", modified.textAlign)
        assertNull(original.classes)
    }

    // --- ParagraphNode ---

    @Test
    fun `ParagraphNode default creation has empty content`() {
        val paragraph = ParagraphNode()
        assertTrue(paragraph.content.isEmpty())
    }

    @Test
    fun `ParagraphNode default creation has empty marks`() {
        val paragraph = ParagraphNode()
        assertTrue(paragraph.marks.isEmpty())
    }

    @Test
    fun `ParagraphNode default attributes has null classes`() {
        val paragraph = ParagraphNode()
        assertNull(paragraph.attributes.classes)
    }

    // --- ParagraphAttributes ---

    @Test
    fun `ParagraphAttributes withClasses creates copy with new classes`() {
        val original = ParagraphAttributes(textAlign = "right")
        val modified = original.withClasses("para-class")
        assertEquals("para-class", modified.classes)
        assertEquals("right", modified.textAlign)
        assertNull(original.classes)
    }

    // --- EmptyDocumentAttributes ---

    @Test
    fun `EmptyDocumentAttributes withClasses creates copy with new classes`() {
        val original = EmptyDocumentAttributes()
        val modified = original.withClasses("doc-class")
        assertNotNull(modified)
        assertEquals("doc-class", modified.classes)
        assertNull(original.classes)
    }
}

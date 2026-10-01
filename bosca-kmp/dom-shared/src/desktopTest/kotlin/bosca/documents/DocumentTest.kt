package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentTest {

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
    fun `Document default attributes is EmptyDocumentAttributes`() {
        val doc = Document()
        assertTrue(doc.attributes is EmptyDocumentAttributes)
    }

    @Test
    fun `Document preserves content`() {
        val child = ParagraphNode()
        val doc = Document(content = listOf(child))
        assertEquals(1, doc.content.size)
    }

    @Test
    fun `Document preserves marks`() {
        val doc = Document(marks = listOf(Bold()))
        assertEquals(1, doc.marks.size)
    }

    @Test
    fun `Content wraps a Document with default`() {
        val content = Content()
        assertTrue(content.document is Document)
    }

    @Test
    fun `Content wraps a specific Document`() {
        val doc = Document(content = listOf(ParagraphNode()))
        val content = Content(document = doc)
        assertEquals(1, content.document.content.size)
    }
}

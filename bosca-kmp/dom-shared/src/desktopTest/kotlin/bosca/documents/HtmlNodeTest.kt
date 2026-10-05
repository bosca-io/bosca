package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlNodeTest {

    @Test
    fun `HtmlNode preserves html field`() {
        val node = HtmlNode(html = "<p>Hello</p>")
        assertEquals("<p>Hello</p>", node.html)
    }

    @Test
    fun `HtmlNode default creation has empty content`() {
        val node = HtmlNode(html = "<br/>")
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `HtmlNode default creation has empty marks`() {
        val node = HtmlNode(html = "<br/>")
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `HtmlNode default attributes is EmptyDocumentAttributes`() {
        val node = HtmlNode(html = "<br/>")
        assertTrue(node.attributes is EmptyDocumentAttributes)
    }

    @Test
    fun `HtmlNode preserves marks`() {
        val node = HtmlNode(html = "<b>bold</b>", marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }

    @Test
    fun `HtmlNode preserves content`() {
        val child = ParagraphNode()
        val node = HtmlNode(html = "<div></div>", content = listOf(child))
        assertEquals(1, node.content.size)
    }
}

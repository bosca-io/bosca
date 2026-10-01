package bosca.documents

import bosca.content.metadata.service.BibleService
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NodeConverter must survive the arbitrary HTML that external sources (feed articles especially) carry:
 * unmodeled elements unwrap to their children and script/style content is dropped — never thrown. A
 * single `<figure>` previously crashed the whole conversion, leaving feed items with no body.
 */
class NodeConverterTest {

    private val converter = NodeConverter(mockk<BibleService>(relaxed = true))

    private fun convert(html: String): Document =
        converter.convertDocument(HtmlNode(html = html)) as Document

    @Test
    fun `unwraps an unsupported figure, preserving the image and caption`() {
        val doc = convert("""<figure><img src="a.jpg"><figcaption>A caption</figcaption></figure>""")
        assertTrue(doc.content.any { it is ImageNode }, "image preserved")
        assertTrue(textOf(doc).contains("A caption"), "caption text preserved, was: ${textOf(doc)}")
    }

    @Test
    fun `drops script content instead of leaking it as text`() {
        val doc = convert("<p>Hello</p><script>alert('x')</script>")
        assertEquals("Hello", textOf(doc).trim())
    }

    @Test
    fun `converts a basic heading and paragraph without error`() {
        val doc = convert("<h2>Title</h2><p>Body</p>")
        assertTrue(doc.content.any { it is HeadingNode }, "heading")
        assertTrue(doc.content.any { it is ParagraphNode }, "paragraph")
    }

    private fun textOf(node: DocumentNode): String = buildString { collect(node, this) }

    private fun collect(node: DocumentNode, sb: StringBuilder) {
        if (node is TextNode) sb.append(node.text)
        node.content.forEach { collect(it, sb) }
    }
}

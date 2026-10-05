package bosca.documents

import bosca.documents.marks.Bold
import bosca.documents.marks.Code
import bosca.documents.marks.Link
import bosca.documents.marks.LinkAttributes
import bosca.documents.marks.Strike
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentSerializationTest {

    private val json = Json {
        serializersModule = DocumentSerializers
        ignoreUnknownKeys = true
    }

    @Test
    fun `serialize and deserialize Document with heading and paragraph content`() {
        val doc = Document(
            content = listOf(
                HeadingNode(
                    attributes = HeadingAttributes(level = 1),
                    content = listOf(
                        TextNode(text = "Hello")
                    )
                ),
                ParagraphNode(
                    content = listOf(
                        TextNode(text = "World")
                    )
                )
            )
        )

        val serialized = json.encodeToString<DocumentNode>(doc)
        val deserialized = json.decodeFromString<DocumentNode>(serialized)

        assertTrue(deserialized is Document)
        assertEquals(2, deserialized.content.size)

        val heading = deserialized.content[0]
        assertTrue(heading is HeadingNode)
        assertEquals(1, heading.attributes.level)
        assertEquals(1, heading.content.size)

        val headingText = heading.content[0]
        assertTrue(headingText is TextNode)
        assertEquals("Hello", headingText.text)

        val paragraph = deserialized.content[1]
        assertTrue(paragraph is ParagraphNode)
        assertEquals(1, paragraph.content.size)

        val paragraphText = paragraph.content[0]
        assertTrue(paragraphText is TextNode)
        assertEquals("World", paragraphText.text)
    }

    @Test
    fun `serialize and deserialize Bold mark`() {
        val bold = Bold()
        val serialized = json.encodeToString<bosca.documents.marks.Mark>(bold)
        val deserialized = json.decodeFromString<bosca.documents.marks.Mark>(serialized)

        assertTrue(deserialized is Bold)
    }

    @Test
    fun `serialize and deserialize Link mark with attributes`() {
        val link = Link(
            attributes = LinkAttributes(
                href = "https://example.com",
                url = "https://example.com",
                rel = "noopener",
                target = "_blank"
            )
        )
        val serialized = json.encodeToString<bosca.documents.marks.Mark>(link)
        val deserialized = json.decodeFromString<bosca.documents.marks.Mark>(serialized)

        assertTrue(deserialized is Link)
        val attrs = deserialized.attributes
        assertNotNull(attrs)
        assertTrue(attrs is LinkAttributes)
        assertEquals("https://example.com", attrs.href)
        assertEquals("https://example.com", attrs.url)
        assertEquals("noopener", attrs.rel)
        assertEquals("_blank", attrs.target)
    }

    @Test
    fun `deserialize Code mark from TipTap wire shape`() {
        // Wire shape produced by @tiptap/extension-code (loaded via StarterKit).
        val wire = """{"type":"code"}"""
        val mark = json.decodeFromString<bosca.documents.marks.Mark>(wire)
        assertTrue(mark is Code)
    }

    @Test
    fun `deserialize Strike mark from TipTap wire shape`() {
        // Wire shape produced by @tiptap/extension-strike (loaded via StarterKit).
        val wire = """{"type":"strike"}"""
        val mark = json.decodeFromString<bosca.documents.marks.Mark>(wire)
        assertTrue(mark is Strike)
    }

    @Test
    fun `deserialize CodeBlock node from TipTap wire shape with language attr`() {
        // Wire shape produced by @tiptap/extension-code-block (loaded via StarterKit).
        val wire = """
            {"type":"codeBlock","attrs":{"language":"kotlin"},
             "content":[{"type":"text","text":"val x = 1"}]}
        """.trimIndent()
        val node = json.decodeFromString<DocumentNode>(wire)
        assertTrue(node is CodeBlockNode)
        assertEquals("kotlin", node.attributes.language)
        assertEquals(1, node.content.size)
        val text = node.content[0]
        assertTrue(text is TextNode)
        assertEquals("val x = 1", text.text)
    }

    @Test
    fun `deserialize CodeBlock node with null language attr`() {
        // Studio may emit codeBlocks without a language set.
        val wire = """{"type":"codeBlock","attrs":{"language":null},"content":[]}"""
        val node = json.decodeFromString<DocumentNode>(wire)
        assertTrue(node is CodeBlockNode)
        assertEquals(null, node.attributes.language)
    }

    @Test
    fun `deserialize TableHeader node from TipTap wire shape`() {
        // Wire shape produced by @tiptap/extension-table-header.
        val wire = """
            {"type":"tableHeader","attrs":{"colspan":1,"rowspan":1},
             "content":[{"type":"paragraph","content":[{"type":"text","text":"H"}]}]}
        """.trimIndent()
        val node = json.decodeFromString<DocumentNode>(wire)
        assertTrue(node is TableHeaderNode)
        assertEquals(1, node.attributes.colspan)
        assertEquals(1, node.attributes.rowspan)
    }

    @Test
    fun `round-trip Document with all four newly-registered types`() {
        // End-to-end guard: a document mixing Code mark, Strike mark, CodeBlock node,
        // and a TableHeader inside a table round-trips cleanly. This is the exact
        // class of doc that previously crashed setMetadataDocument server-side.
        val doc = Document(
            content = listOf(
                ParagraphNode(
                    content = listOf(
                        TextNode(text = "inline ", marks = emptyList()),
                        TextNode(text = "code", marks = listOf(Code())),
                        TextNode(text = " and ", marks = emptyList()),
                        TextNode(text = "strike", marks = listOf(Strike())),
                    )
                ),
                CodeBlockNode(
                    attributes = CodeBlockAttributes(language = "kotlin"),
                    content = listOf(TextNode(text = "fun main() {}")),
                ),
                TableNode(
                    content = listOf(
                        TableRowNode(
                            content = listOf(
                                TableHeaderNode(
                                    content = listOf(
                                        ParagraphNode(content = listOf(TextNode(text = "H")))
                                    )
                                )
                            )
                        )
                    )
                ),
            )
        )
        val serialized = json.encodeToString<DocumentNode>(doc)
        val deserialized = json.decodeFromString<DocumentNode>(serialized)
        assertTrue(deserialized is Document)
        assertEquals(3, deserialized.content.size)

        val p = deserialized.content[0]
        assertTrue(p is ParagraphNode)
        val codeText = p.content[1]
        assertTrue(codeText is TextNode)
        assertTrue(codeText.marks.any { it is Code }, "second text run must carry Code mark")
        val strikeText = p.content[3]
        assertTrue(strikeText is TextNode)
        assertTrue(strikeText.marks.any { it is Strike }, "fourth text run must carry Strike mark")

        val codeBlock = deserialized.content[1]
        assertTrue(codeBlock is CodeBlockNode)
        assertEquals("kotlin", codeBlock.attributes.language)

        val table = deserialized.content[2]
        assertTrue(table is TableNode)
        val row = table.content[0]
        assertTrue(row is TableRowNode)
        val header = row.content[0]
        assertTrue(header is TableHeaderNode, "table first-row cell must deserialize as TableHeaderNode")
    }

    @Test
    fun `round-trip Content class wrapping Document`() {
        val content = Content(
            document = Document(
                content = listOf(
                    ParagraphNode(
                        content = listOf(
                            TextNode(text = "Test content")
                        )
                    )
                )
            )
        )

        val serialized = json.encodeToString(content)
        val deserialized = json.decodeFromString<Content>(serialized)

        assertNotNull(deserialized.document)
        assertTrue(deserialized.document is Document)
        val doc = deserialized.document as Document
        assertEquals(1, doc.content.size)

        val paragraph = doc.content[0]
        assertTrue(paragraph is ParagraphNode)
        assertEquals(1, paragraph.content.size)

        val text = paragraph.content[0]
        assertTrue(text is TextNode)
        assertEquals("Test content", text.text)
    }
}

package bosca.documents

import bosca.documents.marks.Bold
import bosca.documents.marks.Code
import bosca.documents.marks.Italic
import bosca.documents.marks.Link
import bosca.documents.marks.LinkAttributes
import bosca.documents.marks.Strike
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarkdownConverterTest {

    @Test
    fun `heading level 1`() {
        val content = Content(document = Document(content = listOf(
            HeadingNode(attributes = HeadingAttributes(level = 1), content = listOf(TextNode(text = "Title")))
        )))
        assertEquals("# Title\n\n", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `heading level 2`() {
        val content = Content(document = Document(content = listOf(
            HeadingNode(attributes = HeadingAttributes(level = 2), content = listOf(TextNode(text = "Section")))
        )))
        assertEquals("## Section\n\n", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `paragraph`() {
        val content = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "Hello world")))
        )))
        assertEquals("Hello world\n\n", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `bold text`() {
        val content = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "bold", marks = listOf(Bold()))))
        )))
        assertEquals("**bold**\n\n", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `italic text`() {
        val content = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "italic", marks = listOf(Italic()))))
        )))
        assertEquals("*italic*\n\n", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `link text`() {
        val content = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(
                TextNode(text = "click here", marks = listOf(Link(attributes = LinkAttributes(href = "https://example.com"))))
            ))
        )))
        assertEquals("[click here](https://example.com)\n\n", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `horizontal rule`() {
        val content = Content(document = Document(content = listOf(
            HorizontalRuleNode()
        )))
        assertEquals("---\n\n", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `spec template structure round-trips`() {
        val content = Content(document = Document(content = listOf(
            HeadingNode(attributes = HeadingAttributes(level = 1), content = listOf(TextNode(text = "Overview"))),
            ParagraphNode(content = listOf(TextNode(text = "This spec covers the login flow."))),
            HeadingNode(attributes = HeadingAttributes(level = 2), content = listOf(TextNode(text = "Goals"))),
            ParagraphNode(content = listOf(TextNode(text = "Improve auth UX."))),
        )))

        val md = MarkdownConverter.toMarkdown(content)
        assertTrue(md.contains("# Overview"))
        assertTrue(md.contains("## Goals"))
        assertTrue(md.contains("This spec covers the login flow."))

        val roundTripped = MarkdownConverter.fromMarkdown(md)
        val md2 = MarkdownConverter.toMarkdown(roundTripped)
        assertEquals(md, md2, "round-trip should produce identical markdown")
    }

    @Test
    fun `fromMarkdown parses headings and paragraphs`() {
        val md = "# Title\n\nSome text\n\n## Section\n\nMore text\n"
        val content = MarkdownConverter.fromMarkdown(md)
        val nodes = content.document.content
        assertEquals(4, nodes.size)
        assertTrue(nodes[0] is HeadingNode)
        assertTrue(nodes[1] is ParagraphNode)
        assertTrue(nodes[2] is HeadingNode)
        assertTrue(nodes[3] is ParagraphNode)
    }

    @Test
    fun `empty document`() {
        val content = Content(document = Document(content = emptyList()))
        assertEquals("", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `bullet list to markdown`() {
        val content = Content(document = Document(content = listOf(
            BulletListNode(content = listOf(
                ListItemNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "one"))))),
                ListItemNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "two"))))),
            ))
        )))
        val md = MarkdownConverter.toMarkdown(content)
        assertEquals("- one\n- two\n\n", md)
    }

    @Test
    fun `ordered list to markdown`() {
        val content = Content(document = Document(content = listOf(
            OrderedListNode(content = listOf(
                ListItemNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "first"))))),
                ListItemNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "second"))))),
            ))
        )))
        val md = MarkdownConverter.toMarkdown(content)
        assertEquals("1. first\n2. second\n\n", md)
    }

    @Test
    fun `task list to markdown`() {
        val content = Content(document = Document(content = listOf(
            TaskListNode(content = listOf(
                TaskItemNode(attributes = TaskItemAttributes(checked = true), content = listOf(ParagraphNode(content = listOf(TextNode(text = "done"))))),
                TaskItemNode(attributes = TaskItemAttributes(checked = false), content = listOf(ParagraphNode(content = listOf(TextNode(text = "todo"))))),
            ))
        )))
        val md = MarkdownConverter.toMarkdown(content)
        assertEquals("- [x] done\n- [ ] todo\n\n", md)
    }

    @Test
    fun `bullet list from markdown produces bulletList nodes`() {
        val md = "- one\n- two\n"
        val content = MarkdownConverter.fromMarkdown(md)
        val list = content.document.content.firstOrNull()
        assertNotNull(list)
        assertTrue(list is BulletListNode, "expected BulletListNode, got ${list::class.simpleName}")
        assertEquals(2, list.content.size)
        list.content.forEach { item ->
            assertTrue(item is ListItemNode)
        }
        assertEquals("one", extractText(list.content[0]))
        assertEquals("two", extractText(list.content[1]))
    }

    @Test
    fun `ordered list from markdown produces orderedList nodes`() {
        val md = "1. alpha\n2. beta\n"
        val content = MarkdownConverter.fromMarkdown(md)
        val list = content.document.content.firstOrNull()
        assertNotNull(list)
        assertTrue(list is OrderedListNode, "expected OrderedListNode, got ${list::class.simpleName}")
        assertEquals(2, list.content.size)
        assertEquals("alpha", extractText(list.content[0]))
        assertEquals("beta", extractText(list.content[1]))
    }

    @Test
    fun `task list from markdown produces taskList nodes`() {
        val md = "- [x] done\n- [ ] todo\n"
        val content = MarkdownConverter.fromMarkdown(md)
        val list = content.document.content.firstOrNull()
        assertNotNull(list)
        assertTrue(list is TaskListNode, "expected TaskListNode, got ${list::class.simpleName}")
        assertEquals(2, list.content.size)
        val first = list.content[0] as TaskItemNode
        val second = list.content[1] as TaskItemNode
        assertEquals(true, first.attributes.checked)
        assertEquals(false, second.attributes.checked)
        assertEquals("done", extractText(first))
        assertEquals("todo", extractText(second))
    }

    @Test
    fun `bullet list round-trips through markdown`() {
        val md = "- one\n- two\n- three\n"
        val parsed = MarkdownConverter.fromMarkdown(md)
        val rendered = MarkdownConverter.toMarkdown(parsed)
        assertEquals("- one\n- two\n- three\n\n", rendered)
    }

    @Test
    fun `table to markdown`() {
        val content = Content(document = Document(content = listOf(
            TableNode(content = listOf(
                TableRowNode(content = listOf(
                    TableHeaderNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "col1"))))),
                    TableHeaderNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "col2"))))),
                )),
                TableRowNode(content = listOf(
                    TableCellNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "a"))))),
                    TableCellNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "b"))))),
                )),
                TableRowNode(content = listOf(
                    TableCellNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "c"))))),
                    TableCellNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "d"))))),
                )),
            ))
        )))
        val md = MarkdownConverter.toMarkdown(content)
        assertEquals(
            "| col1 | col2 |\n| --- | --- |\n| a | b |\n| c | d |\n\n",
            md
        )
    }

    @Test
    fun `table from markdown produces table nodes`() {
        val md = "| col1 | col2 |\n| --- | --- |\n| a | b |\n| c | d |\n"
        val content = MarkdownConverter.fromMarkdown(md)
        val table = content.document.content.firstOrNull()
        assertNotNull(table)
        assertTrue(table is TableNode, "expected TableNode, got ${table::class.simpleName}")
        val rows = table.content.filterIsInstance<TableRowNode>()
        assertEquals(3, rows.size)
        // Header row
        assertEquals(2, rows[0].content.size)
        assertTrue(rows[0].content.all { it is TableHeaderNode })
        assertEquals("col1", extractText(rows[0].content[0]))
        assertEquals("col2", extractText(rows[0].content[1]))
        // Body rows
        assertTrue(rows[1].content.all { it is TableCellNode })
        assertEquals("a", extractText(rows[1].content[0]))
        assertEquals("b", extractText(rows[1].content[1]))
        assertEquals("c", extractText(rows[2].content[0]))
        assertEquals("d", extractText(rows[2].content[1]))
    }

    @Test
    fun `table round-trips through markdown`() {
        val md = "| h1 | h2 |\n| --- | --- |\n| x | y |\n"
        val parsed = MarkdownConverter.fromMarkdown(md)
        val rendered = MarkdownConverter.toMarkdown(parsed)
        // Re-parse the rendered output and make sure the structure survives a second pass.
        val reparsed = MarkdownConverter.fromMarkdown(rendered)
        val rerendered = MarkdownConverter.toMarkdown(reparsed)
        assertEquals(rendered, rerendered, "table markdown should be idempotent")
    }

    @Test
    fun `inline code mark to markdown`() {
        val content = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "code", marks = listOf(Code()))))
        )))
        assertEquals("`code`\n\n", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `strike mark to markdown`() {
        val content = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(TextNode(text = "gone", marks = listOf(Strike()))))
        )))
        assertEquals("~~gone~~\n\n", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `inline code from markdown`() {
        val md = "Try `foo()` now."
        val content = MarkdownConverter.fromMarkdown(md)
        val para = content.document.content[0] as ParagraphNode
        val code = para.content.firstOrNull { it is TextNode && (it as TextNode).marks.any { m -> m is Code } }
        assertNotNull(code, "code-marked text node present")
        assertEquals("foo()", (code as TextNode).text)
    }

    @Test
    fun `strikethrough from markdown`() {
        val md = "This ~~was~~ removed."
        val content = MarkdownConverter.fromMarkdown(md)
        val para = content.document.content[0] as ParagraphNode
        val strike = para.content.firstOrNull { it is TextNode && (it as TextNode).marks.any { m -> m is Strike } }
        assertNotNull(strike, "strike-marked text node present")
        assertEquals("was", (strike as TextNode).text)
    }

    @Test
    fun `code block to markdown with language`() {
        val content = Content(document = Document(content = listOf(
            CodeBlockNode(
                attributes = CodeBlockAttributes(language = "kotlin"),
                content = listOf(TextNode(text = "fun main() {}\n")),
            )
        )))
        assertEquals("```kotlin\nfun main() {}\n```\n\n", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `code block from markdown with language`() {
        val md = "```python\nprint('hi')\n```\n"
        val content = MarkdownConverter.fromMarkdown(md)
        val block = content.document.content[0]
        assertTrue(block is CodeBlockNode, "expected CodeBlockNode, got ${block::class.simpleName}")
        assertEquals("python", block.attributes.language)
        val text = (block.content[0] as TextNode).text
        assertTrue(text.contains("print('hi')"))
    }

    @Test
    fun `image node to markdown`() {
        val content = Content(document = Document(content = listOf(
            ImageNode(attributes = ImageAttributes(src = "https://example.com/x.png", alt = "X"))
        )))
        assertEquals("![X](https://example.com/x.png)\n\n", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `image from markdown produces image node`() {
        val md = "![alt text](https://example.com/img.png)\n"
        val content = MarkdownConverter.fromMarkdown(md)
        // Markdown-images are inline; CommonMark wraps them in a paragraph.
        val para = content.document.content[0] as ParagraphNode
        val image = para.content.firstOrNull { it is ImageNode } as? ImageNode
        assertNotNull(image, "image node present")
        assertEquals("alt text", image.attributes.alt)
        assertEquals("https://example.com/img.png", image.attributes.src)
    }

    @Test
    fun `nested bullet list to markdown indents inner list`() {
        val content = Content(document = Document(content = listOf(
            BulletListNode(content = listOf(
                ListItemNode(content = listOf(
                    ParagraphNode(content = listOf(TextNode(text = "outer"))),
                    BulletListNode(content = listOf(
                        ListItemNode(content = listOf(ParagraphNode(content = listOf(TextNode(text = "inner")))))
                    )),
                )),
            ))
        )))
        val md = MarkdownConverter.toMarkdown(content)
        assertTrue(md.contains("- outer"), "outer item rendered: \n$md")
        assertTrue(md.contains("  - inner"), "inner item indented two spaces: \n$md")
    }

    @Test
    fun `nested bullet list from markdown produces nested structure`() {
        val md = "- outer\n  - inner\n"
        val content = MarkdownConverter.fromMarkdown(md)
        val outer = content.document.content[0] as BulletListNode
        val outerItem = outer.content[0] as ListItemNode
        // Inner list should be a child of the outer list item, not a sibling of the outer list.
        val innerList = outerItem.content.firstOrNull { it is BulletListNode } as? BulletListNode
        assertNotNull(innerList, "inner list nested under outer item: ${outerItem.content}")
        assertEquals("inner", extractText(innerList.content[0]))
    }

    @Test
    fun `raw HTML in markdown input is escaped as text not parsed as nodes`() {
        // Without escapeHtml(true) on the renderer, raw HTML flowed verbatim into the
        // HtmlConverter — a `<script>` tag became a node type the polymorphic deserializer
        // rejects, crashing the call. Now raw HTML survives as plain text, which is both
        // safer and more graceful for callers who happened to paste HTML.
        val cases = listOf(
            "Some text\n\n<script>alert(1)</script>\n\nMore text",
            "An <iframe src=evil></iframe> here.",
            "Inline <b>bold-via-html</b> reference",
        )
        for (md in cases) {
            // Each must parse without throwing — this is the entire safety property.
            val content = MarkdownConverter.fromMarkdown(md)
            // And the alarming text should be preserved as literal text inside the doc,
            // not silently discarded.
            val flat = StringBuilder()
            fun walk(n: DocumentNode) {
                if (n is TextNode) flat.append(n.text)
                else n.content.forEach(::walk)
            }
            walk(content.document)
            // We don't assert on the exact text shape (CommonMark may HTML-entity-encode some
            // chars during the round-trip) — just that no parse error and core text survives.
            assertTrue(flat.toString().isNotBlank(), "expected non-empty text from `$md`, got `$flat`")
        }
    }

    @Test
    fun `hard break to markdown emits two-space line break`() {
        val content = Content(document = Document(content = listOf(
            ParagraphNode(content = listOf(
                TextNode(text = "first"),
                HardBreakNode(),
                TextNode(text = "second"),
            ))
        )))
        // GFM hard break: trailing two spaces + newline within a paragraph.
        assertEquals("first  \nsecond\n\n", MarkdownConverter.toMarkdown(content))
    }

    @Test
    fun `hard break round-trips through markdown`() {
        val md = "first  \nsecond\n"
        val parsed = MarkdownConverter.fromMarkdown(md)
        val para = parsed.document.content[0] as ParagraphNode
        assertTrue(
            para.content.any { it is HardBreakNode },
            "hard break should survive a parse: ${para.content.map { it::class.simpleName }}",
        )
        // And idempotent on a second pass.
        val rendered = MarkdownConverter.toMarkdown(parsed)
        val rerendered = MarkdownConverter.toMarkdown(MarkdownConverter.fromMarkdown(rendered))
        assertEquals(rendered, rerendered)
    }

    @Test
    fun `structural round-trip through markdown`() {
        // For each construct, parse -> render -> re-parse, then assert structural equality
        // on the re-parsed tree. This catches drift introduced by half-implemented support.
        val cases = listOf(
            "# heading\n\nbody\n",
            "- a\n- b\n- c\n",
            "1. one\n2. two\n",
            "- [x] done\n- [ ] todo\n",
            "| h1 | h2 |\n| --- | --- |\n| a | b |\n",
            "Try `code` here.\n",
            "Old ~~content~~ removed.\n",
            "**bold** and *italic*.\n",
            "[link](https://example.com)\n",
            "```kotlin\nval x = 1\n```\n",
            "![alt](https://example.com/x.png)\n",
            "> a quote\n",
            "---\n",
            "- outer\n  - inner\n",
        )
        for (md in cases) {
            val first = MarkdownConverter.toMarkdown(MarkdownConverter.fromMarkdown(md))
            val second = MarkdownConverter.toMarkdown(MarkdownConverter.fromMarkdown(first))
            assertEquals(first, second, "round-trip should be idempotent for case:\n$md")
        }
    }

    private fun extractText(node: DocumentNode): String {
        val sb = StringBuilder()
        fun walk(n: DocumentNode) {
            if (n is TextNode) sb.append(n.text)
            else n.content.forEach(::walk)
        }
        walk(node)
        return sb.toString()
    }
}

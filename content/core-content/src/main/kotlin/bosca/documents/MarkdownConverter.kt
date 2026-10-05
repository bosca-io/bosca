package bosca.documents

import bosca.documents.html.DocumentHtmlConverter
import bosca.documents.marks.Bold
import bosca.documents.marks.Code
import bosca.documents.marks.Italic
import bosca.documents.marks.Link
import bosca.documents.marks.Mark
import bosca.documents.marks.Strike
import bosca.documents.marks.Underline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

object MarkdownConverter {

    private val markdownExtensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
        AutolinkExtension.create(),
    )

    private val markdownParser: Parser = Parser.builder().extensions(markdownExtensions).build()

    /**
     * `escapeHtml(true)` converts raw HTML in markdown input into literal text. Without it,
     * a markdown body containing `<script>...</script>` (or any other tag whose name we
     * don't recognize) flows verbatim into [DocumentHtmlConverter], which produces a
     * polymorphic node type the [Content.serializer] cannot resolve — failing the entire
     * `setMetadataMarkdown` call with `JsonDecodingException`. Treating raw HTML as text
     * is both safer (no script vector via markdown) and more graceful (the surrounding
     * content survives instead of the whole call crashing).
     */
    private val htmlRenderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(markdownExtensions)
        .escapeHtml(true)
        .build()

    private val docJson = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    fun toMarkdown(content: Content): String {
        val sb = StringBuilder()
        renderNode(content.document, sb)
        return sb.toString()
    }

    /**
     * Parses a markdown string into a Bosca [Content] tree. Routes through CommonMark (CommonMark
     * spec + GFM tables, strikethrough, task lists, autolink) → HTML → [DocumentHtmlConverter] →
     * the structured Content model so we get a well-formed TipTap document for free.
     */
    fun fromMarkdown(markdown: String): Content {
        val parsed = markdownParser.parse(markdown)
        val html = htmlRenderer.render(parsed)
        val doc = DocumentHtmlConverter.fromHtml(html)
        // DocumentHtmlConverter emits {"type":"doc","content":[...]} which matches Document's @SerialName("doc").
        // Wrap as Content via JSON to leverage the polymorphic deserializer.
        val wrapped = buildJsonObject { put("document", doc) }
        return docJson.decodeFromJsonElement(Content.serializer(), wrapped)
    }

    private fun renderNode(node: DocumentNode, sb: StringBuilder, indent: String = "") {
        when (node) {
            is Document -> node.content.forEach { renderNode(it, sb, indent) }
            is HeadingNode -> {
                val prefix = "#".repeat(node.attributes.level)
                sb.append("$prefix ")
                node.content.forEach { renderInline(it, sb) }
                sb.append("\n\n")
            }
            is ParagraphNode -> {
                node.content.forEach { renderInline(it, sb) }
                sb.append("\n\n")
            }
            is BulletListNode -> renderBulletList(node, sb, indent)
            is OrderedListNode -> renderOrderedList(node, sb, indent)
            is TaskListNode -> renderTaskList(node, sb, indent)
            is HorizontalRuleNode -> sb.append("---\n\n")
            is BlockquoteNode -> {
                node.content.forEach { child ->
                    sb.append("> ")
                    renderInline(child, sb)
                    sb.append("\n")
                }
                sb.append("\n")
            }
            is HardBreakNode -> sb.append("\n")
            is TableNode -> renderTable(node, sb)
            is CodeBlockNode -> renderCodeBlock(node, sb)
            is ImageNode -> renderImage(node, sb)
            else -> node.content.forEach { renderNode(it, sb, indent) }
        }
    }

    private fun renderBulletList(node: BulletListNode, sb: StringBuilder, indent: String) {
        node.content.forEach { item ->
            sb.append(indent).append("- ")
            renderListItemContent(item, sb, indent)
        }
        if (indent.isEmpty()) sb.append("\n")
    }

    private fun renderOrderedList(node: OrderedListNode, sb: StringBuilder, indent: String) {
        node.content.forEachIndexed { idx, item ->
            sb.append(indent).append("${idx + 1}. ")
            renderListItemContent(item, sb, indent)
        }
        if (indent.isEmpty()) sb.append("\n")
    }

    private fun renderTaskList(node: TaskListNode, sb: StringBuilder, indent: String) {
        node.content.forEach { item ->
            val checked = (item as? TaskItemNode)?.attributes?.checked == true
            sb.append(indent).append(if (checked) "- [x] " else "- [ ] ")
            renderListItemContent(item, sb, indent)
        }
        if (indent.isEmpty()) sb.append("\n")
    }

    /**
     * Renders the contents of a list item. The bullet/number prefix has already been
     * written; this emits the inline portion of the first paragraph immediately after
     * the marker, then recursively renders any nested lists or other block children
     * with one extra indent level.
     */
    private fun renderListItemContent(item: DocumentNode, sb: StringBuilder, indent: String) {
        var firstInlineWritten = false
        for (child in item.content) {
            when (child) {
                is ParagraphNode -> {
                    if (firstInlineWritten) {
                        sb.append(indent).append("  ")
                    }
                    child.content.forEach { renderInline(it, sb) }
                    sb.append("\n")
                    firstInlineWritten = true
                }
                is BulletListNode, is OrderedListNode, is TaskListNode -> {
                    if (!firstInlineWritten) {
                        sb.append("\n")
                        firstInlineWritten = true
                    }
                    renderNode(child, sb, "$indent  ")
                }
                else -> {
                    if (firstInlineWritten) sb.append(indent).append("  ")
                    renderInline(child, sb)
                    sb.append("\n")
                    firstInlineWritten = true
                }
            }
        }
        if (!firstInlineWritten) sb.append("\n")
    }

    private fun renderCodeBlock(node: CodeBlockNode, sb: StringBuilder) {
        val lang = node.attributes.language.orEmpty()
        sb.append("```").append(lang).append("\n")
        node.content.forEach { child ->
            if (child is TextNode) sb.append(child.text)
        }
        if (!sb.endsWith("\n")) sb.append("\n")
        sb.append("```\n\n")
    }

    private fun renderImage(node: ImageNode, sb: StringBuilder) {
        val alt = node.attributes.alt.orEmpty()
        val src = node.attributes.src.orEmpty()
        val title = node.attributes.title
        if (title.isNullOrEmpty()) {
            sb.append("![").append(alt).append("](").append(src).append(")\n\n")
        } else {
            sb.append("![").append(alt).append("](").append(src).append(" \"").append(title).append("\")\n\n")
        }
    }

    private fun renderTable(node: TableNode, sb: StringBuilder) {
        val rows = node.content.filterIsInstance<TableRowNode>()
        if (rows.isEmpty()) return
        val isHeader: (TableRowNode) -> Boolean = { row ->
            row.content.isNotEmpty() && row.content.all { it is TableHeaderNode }
        }
        val (headerRows, bodyRows) = rows.partition(isHeader)
        // GFM tables require exactly one header row. If the source has none (or many),
        // synthesize a blank header sized to the widest body row to keep the output parseable.
        val header = headerRows.firstOrNull()
            ?: TableRowNode(content = List(rows.maxOf { it.content.size }) { TableHeaderNode() })
        val body = if (headerRows.isEmpty()) rows else bodyRows
        val columnCount = header.content.size.coerceAtLeast(body.maxOfOrNull { it.content.size } ?: 0)
        sb.append("| ").append((0 until columnCount).joinToString(" | ") { idx ->
            cellText(header.content.getOrNull(idx))
        }).append(" |\n")
        sb.append("| ").append((0 until columnCount).joinToString(" | ") { "---" }).append(" |\n")
        body.forEach { row ->
            sb.append("| ").append((0 until columnCount).joinToString(" | ") { idx ->
                cellText(row.content.getOrNull(idx))
            }).append(" |\n")
        }
        sb.append("\n")
    }

    private fun cellText(cell: DocumentNode?): String {
        if (cell == null) return ""
        val sb = StringBuilder()
        cell.content.forEach { child ->
            when (child) {
                is ParagraphNode -> child.content.forEach { renderInline(it, sb) }
                else -> renderInline(child, sb)
            }
        }
        // GFM cells cannot contain literal newlines or unescaped pipes.
        return sb.toString().replace("\n", " ").replace("|", "\\|").trim()
    }

    private fun renderInline(node: DocumentNode, sb: StringBuilder) {
        when (node) {
            is TextNode -> {
                // CommonMark + HtmlConverter leaves a leading newline on text that follows
                // a `<br/>` (the source HTML's cosmetic newline becomes content). Strip it
                // so the markdown rendering is idempotent.
                val raw = if (sb.endsWith("  \n")) node.text.trimStart('\n', '\r') else node.text
                if (raw.isEmpty()) return
                var text = raw
                // The order matters: code is innermost (no escaping inside backticks),
                // strike/em/strong/underline can wrap each other, link is outermost so
                // its `[...]` brackets enclose the formatted text.
                val orderedMarks = node.marks.sortedBy { markPriority(it) }
                for (mark in orderedMarks) {
                    text = wrapMark(mark, text)
                }
                sb.append(text)
            }
            is MentionNode -> {
                val label = node.attributes.label ?: node.attributes.id ?: ""
                sb.append("@$label")
            }
            is ImageNode -> {
                // Inline images stay on the same line as surrounding text.
                val alt = node.attributes.alt.orEmpty()
                val src = node.attributes.src.orEmpty()
                sb.append("![").append(alt).append("](").append(src).append(")")
            }
            is HardBreakNode -> sb.append("  \n")
            is ParagraphNode -> node.content.forEach { renderInline(it, sb) }
            else -> node.content.forEach { renderInline(it, sb) }
        }
    }

    private fun markPriority(mark: Mark): Int = when (mark) {
        is Code -> 0
        is Strike -> 1
        is Bold -> 2
        is Italic -> 3
        is Underline -> 4
        is Link -> 5
        else -> 6
    }

    private fun wrapMark(mark: Mark, text: String): String = when (mark) {
        is Bold -> "**$text**"
        is Italic -> "*$text*"
        is Underline -> "__${text}__"
        is Code -> "`$text`"
        is Strike -> "~~$text~~"
        is Link -> {
            val href = mark.attributes?.href ?: mark.attributes?.url ?: ""
            "[$text]($href)"
        }
        else -> text
    }

    private fun parseInline(text: String): List<DocumentNode> =
        listOf(TextNode(text = text))
}

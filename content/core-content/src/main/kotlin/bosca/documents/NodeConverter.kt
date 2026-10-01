package bosca.documents

import bosca.content.metadata.model.Bible
import bosca.content.metadata.service.BibleService
import bosca.documents.marks.Bold
import bosca.documents.marks.Italic
import bosca.documents.marks.Link
import bosca.documents.marks.LinkAttributes
import bosca.documents.marks.Mark
import bosca.documents.marks.Superscript
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import org.slf4j.LoggerFactory
import java.util.*

class NodeConverter(
    private val bibleService: BibleService
) {

    suspend fun convert(node: DocumentNode, bible: Bible? = null): DocumentNode {
        if (node.content.isEmpty()) return node

        val children = mutableListOf<DocumentNode>()
        for (child in node.content) {
            when (child) {
                is HtmlNode -> children.addAll(convert(child))
                else -> children.add(convert(child, bible))
            }
        }

        val className = node.attributes.classes
        val marks = node.marks

        return when (node) {
            is Document -> Document(node.attributes.withClasses(className), children, marks)
            is TextNode -> TextNode(node.attributes.withClasses(className), marks = marks, text = node.text)
            is HardBreakNode -> HardBreakNode(node.attributes.withClasses(className), children, marks)
            is HeadingNode -> HeadingNode(node.attributes.withClasses(className), children, marks)
            is HorizontalRuleNode -> HorizontalRuleNode(node.attributes.withClasses(className), children, marks)
            is HtmlNode -> throw IllegalArgumentException("HTML node is not supported.")
            is ImageNode -> ImageNode(node.attributes.withClasses(className), children, marks)
            is ContainerNode -> {
                val references = if (bible != null && node.attributes.name?.lowercase() == "bible") {
                    node.attributes.references?.mapNotNull {
                        try {
                            val usfm = bibleService.getReferences(bible, it).firstOrNull()?.usfm
                            if (usfm == null) {
                                log.error("INVALID REFERENCE: $it")
                            }
                            usfm
                        } catch (e: Exception) {
                            log.error("INVALID REFERENCE: $it, ERROR: ${e.message}")
                            null
                        }
                    }
                } else {
                    null
                }
                ContainerNode(node.attributes.withClasses(className).withReferences(bible?.metadataId, references), children, marks)
            }

            is BlockquoteNode -> BlockquoteNode(node.attributes.withClasses(className), children, marks)
            is ListItemNode -> {
                if (children.firstOrNull() !is ParagraphNode) {
                    ListItemNode(
                        node.attributes.withClasses(className),
                        listOf(
                            ParagraphNode(
                                content = children
                            )
                        ), marks
                    )
                } else {
                    ListItemNode(node.attributes.withClasses(className), children, marks)
                }
            }

            is OrderedListNode -> OrderedListNode(node.attributes.withClasses(className), children, marks)
            is ParagraphNode -> ParagraphNode(node.attributes.withClasses(className), children, marks)
            is BulletListNode -> BulletListNode(node.attributes.withClasses(className), children, marks)
            is BibleNode -> BibleNode(node.attributes.withClasses(className), children, marks)
            is TableNode -> TableNode(node.attributes.withClasses(className), children, marks)
            is TableRowNode -> TableRowNode(node.attributes.withClasses(className), children, marks)
            is TableCellNode -> TableCellNode(node.attributes.withClasses(className), children, marks)
            is TableHeaderNode -> TableHeaderNode(node.attributes.withClasses(className), children, marks)
            is MentionNode -> MentionNode(node.attributes.withClasses(className), children, marks)
            is TaskListNode -> TaskListNode(node.attributes.withClasses(className), children, marks)
            is TaskItemNode -> TaskItemNode(node.attributes.withClasses(className), children, marks)
            is CodeBlockNode -> CodeBlockNode(node.attributes.withClasses(className), children, marks)
        }
    }

    fun convertDocument(node: HtmlNode): DocumentNode {
        return Document(content = convert(node))
    }

    fun convert(node: HtmlNode): List<DocumentNode> {
        val document = Ksoup.parse(node.html)
        val nodes = mutableListOf<DocumentNode>()
        val stack = ArrayDeque<Mark>()
        document.body().childNodes().forEach {
            nodes.addAll(convert(it, stack, false))
        }
        val finalNodes = mutableListOf<DocumentNode>()
        for (node in nodes) {
            if (node is TextNode) {
                finalNodes.add(ParagraphNode(content = listOf(node)))
            } else {
                finalNodes.add(node)
            }
        }
        return finalNodes
    }

    private fun convert(node: Node, stack: ArrayDeque<Mark>, inBlock: Boolean): List<DocumentNode> {
        return when (node) {
            is com.fleeksoft.ksoup.nodes.TextNode -> {
                val txt = TextNode(
                    text = node.text()
                        .replace("&amp;", "&")
                        .replace("&#8217;", "’")
                        .replace("&#8220;", "“")
                        .replace("&#8221;", "”")
                        .replace("&#8230;", "…"),
                    marks = stack.toList()
                )
                if (txt.text.trim().isEmpty() || txt.text == "&nbsp;" || txt.text == "\n") return emptyList()
                listOf(txt)
            }

            is com.fleeksoft.ksoup.nodes.Comment -> {
                emptyList()
            }

            is Element -> when (node.tagName()) {
                "h1" -> listOf(
                    HeadingNode(
                        attributes = HeadingAttributes(level = 1),
                        content = node.childNodes().flatMap { convert(it, stack, inBlock) },
                    )
                )

                "h2" -> listOf(
                    HeadingNode(
                        attributes = HeadingAttributes(level = 2),
                        content = node.childNodes().flatMap { convert(it, stack, inBlock) },
                    )
                )

                "h3" -> listOf(
                    HeadingNode(
                        attributes = HeadingAttributes(level = 3),
                        content = node.childNodes().flatMap { convert(it, stack, inBlock) },
                    )
                )

                "h4" -> listOf(
                    HeadingNode(
                        attributes = HeadingAttributes(level = 4),
                        content = node.childNodes().flatMap { convert(it, stack, inBlock) },
                    )
                )

                "h5" -> listOf(
                    HeadingNode(
                        attributes = HeadingAttributes(level = 5),
                        content = node.childNodes().flatMap { convert(it, stack, inBlock) },
                    )
                )

                "h6" -> listOf(
                    HeadingNode(
                        attributes = HeadingAttributes(level = 6),
                        content = node.childNodes().flatMap { convert(it, stack, inBlock) },
                    )
                )

                "p" -> {
                    ParagraphNode(
                        content = node.childNodes().flatMap { convert(it, stack, true) },
                    ).takeIf { it.content.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
                }

                "br" -> listOf(HardBreakNode())

                "hr" -> listOf(HorizontalRuleNode())

                "ol" -> OrderedListNode(
                    attributes = OrderedListAttributes(start = node.attr("start").toIntOrNull()),
                    content = node.childNodes().flatMap { convert(it, stack, inBlock) },
                ).takeIf { it.content.isNotEmpty() }?.let { listOf(it) } ?: emptyList()

                "ul" -> BulletListNode(
                    content = node.childNodes().flatMap { convert(it, stack, inBlock) },
                ).takeIf { it.content.isNotEmpty() }?.let { listOf(it) } ?: emptyList()

                "li" -> listOf(
                    ListItemNode(
                        content = listOf(
                            ParagraphNode(
                                content = node.childNodes().flatMap { convert(it, stack, true) }
                            )
                        )
                    )
                )

                "img" -> listOf(
                    ImageNode(
                        attributes = ImageAttributes(
                            src = node.attr("src")
                        )
                    )
                )

                "a" -> {
                    stack.push(
                        Link(
                            LinkAttributes(
                                href = node.attr("href"),
                                url = node.attr("url"),
                            )
                        )
                    )
                    val nodes = node.childNodes().flatMap { convert(it, stack, inBlock) }
                    stack.pop()
                    nodes
                }

                "sup" -> {
                    stack.push(Superscript())
                    val nodes = node.childNodes().flatMap { convert(it, stack, inBlock) }
                    stack.pop()
                    nodes
                }

                "b", "strong" -> {
                    stack.push(Bold())
                    val nodes = node.childNodes().flatMap { convert(it, stack, inBlock) }
                    stack.pop()
                    nodes
                }

                "i", "em" -> {
                    stack.push(Italic())
                    val nodes = node.childNodes().flatMap { convert(it, stack, inBlock) }
                    stack.pop()
                    nodes
                }

                "section", "div" -> if (inBlock) {
                    node.childNodes().flatMap { convert(it, stack, inBlock) } + listOf(HardBreakNode())
                } else {
                    ParagraphNode(
                        content = node.childNodes().flatMap { convert(it, stack, true) },
                    ).takeIf { it.content.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
                }

                "span" -> node.childNodes().flatMap { convert(it, stack, inBlock) }

                "blockquote" -> BlockquoteNode(
                    content = node.childNodes().flatMap { convert(it, stack, inBlock) },
                ).takeIf { it.content.isNotEmpty() }?.let { listOf(it) } ?: emptyList()

                // Elements whose contents must never surface as document text.
                "script", "style", "noscript", "template", "iframe", "svg", "head" -> emptyList()

                // Unknown/unsupported elements (figure, figcaption, aside, picture, header, footer, …):
                // unwrap to their children rather than failing the whole conversion. External HTML — feed
                // articles especially — is full of tags this converter doesn't model; keeping the inner
                // content while dropping the wrapper is the safe degrade instead of crashing the import.
                else -> node.childNodes().flatMap { convert(it, stack, inBlock) }
            }

            // Non-element, non-text nodes (DataNode, DocumentType, CDATA, …): skip rather than fail.
            else -> emptyList()
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(NodeConverter::class.java)
    }
}
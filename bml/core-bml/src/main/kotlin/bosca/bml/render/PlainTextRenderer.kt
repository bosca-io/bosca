package bosca.bml.render

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * Projects rendered HTML into the readable plain-text alternative used by BML emails.
 *
 * The projection preserves useful document structure, list markers, table columns, image
 * alternatives, and link destinations. Document plumbing and hidden content are omitted.
 */
object PlainTextRenderer {

    private val SKIPPED_ELEMENTS = setOf(
        "canvas", "head", "noscript", "script", "style", "svg", "template",
    )
    private val PARAGRAPH_ELEMENTS = setOf(
        "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "p", "pre",
    )
    private val BLOCK_ELEMENTS = setOf(
        "address", "article", "aside", "details", "div", "figcaption", "figure", "footer",
        "header", "main", "nav", "section", "summary",
    )

    fun render(html: String): String {
        val state = RenderState()
        Ksoup.parse(html).body().childNodes.forEach { renderNode(it, state) }
        while (state.links.isNotEmpty()) appendLinkDestination(state.output, state.links.removeLast())
        return state.output.finish()
    }

    private fun renderNode(node: Node, state: RenderState) {
        when (node) {
            is TextNode -> state.output.text(node.getWholeText())
            is Element -> renderElement(node, state)
        }
    }

    private fun renderElement(element: Element, state: RenderState) {
        val name = element.normalName()
        if (name in SKIPPED_ELEMENTS || isHidden(element)) return

        openElement(element, name, state)
        element.childNodes.forEach { renderNode(it, state) }
        closeElement(name, state)
    }

    private fun openElement(element: Element, name: String, state: RenderState) {
        val output = state.output
        when (name) {
            "a" -> state.links += LinkContext(output.length, element.attr("href"))
            "br", "wbr" -> output.lineBreak()
            "hr" -> {
                output.blankLine()
                output.text("---")
                output.blankLine()
            }
            "img" -> output.imageAlternative(element.attr("alt"))
            "ul" -> {
                output.lineBreak()
                state.lists += ListContext(ordered = false)
            }
            "ol" -> {
                output.lineBreak()
                state.lists += ListContext(
                    ordered = true,
                    next = element.attr("start").toIntOrNull() ?: 1,
                )
            }
            "li" -> {
                output.lineBreak()
                val list = state.lists.lastOrNull() ?: ListContext(ordered = false)
                element.attr("value").toIntOrNull()?.let { list.next = it }
                output.linePrefix("  ".repeat((state.lists.size - 1).coerceAtLeast(0)))
                output.text(if (list.ordered) "${list.next++}. " else "- ")
            }
            "dl" -> output.blankLine()
            "dt" -> output.lineBreak()
            "dd" -> {
                output.lineBreak()
                output.linePrefix("  ")
            }
            "table" -> output.blankLine()
            "tr" -> {
                output.lineBreak()
                state.rowCellCounts += 0
            }
            "td", "th" -> {
                if (state.rowCellCounts.isNotEmpty()) {
                    val count = state.rowCellCounts.removeLast()
                    if (count > 0) output.cellSeparator()
                    state.rowCellCounts += count + 1
                }
            }
            "blockquote" -> {
                output.blankLine()
                output.text("> ")
            }
            in PARAGRAPH_ELEMENTS -> output.blankLine()
            in BLOCK_ELEMENTS -> output.lineBreak()
        }
    }

    private fun closeElement(name: String, state: RenderState) {
        val output = state.output
        when (name) {
            "a" -> if (state.links.isNotEmpty()) {
                appendLinkDestination(output, state.links.removeLast())
            }
            "li", "dt", "dd" -> output.lineBreak()
            "ul", "ol" -> {
                if (state.lists.isNotEmpty()) state.lists.removeLast()
                if (state.lists.isEmpty()) output.blankLine() else output.lineBreak()
            }
            "dl", "table" -> output.blankLine()
            "tr" -> {
                if (state.rowCellCounts.isNotEmpty()) state.rowCellCounts.removeLast()
                output.lineBreak()
            }
            in PARAGRAPH_ELEMENTS -> {
                if (state.lists.isEmpty()) output.blankLine() else output.lineBreak()
            }
            in BLOCK_ELEMENTS -> output.lineBreak()
        }
    }

    private fun appendLinkDestination(output: TextOutput, link: LinkContext) {
        output.flushPendingImageAlternative()
        val href = link.href.trim().takeIf(::isUsefulLink) ?: return
        val destination = when {
            href.startsWith("mailto:", ignoreCase = true) -> href.substringAfter(':')
            href.startsWith("tel:", ignoreCase = true) -> href.substringAfter(':')
            else -> href
        }
        val label = output.substring(link.textStart)
            .replace(Regex("\\s+"), " ")
            .trim()
        when {
            label.isEmpty() -> output.text(destination)
            !label.equals(destination, ignoreCase = true) &&
                !label.equals(href, ignoreCase = true) -> output.text(" ($destination)")
        }
    }

    private fun isUsefulLink(href: String): Boolean {
        if (href.isBlank() || href.startsWith('#')) return false
        val scheme = href.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme !in setOf("cid", "data", "javascript")
    }

    private fun isHidden(element: Element): Boolean {
        if (element.hasAttr("hidden") || element.attr("aria-hidden").equals("true", ignoreCase = true)) return true
        return element.attr("style").split(';').any { declaration ->
            val (property, value) = declaration.split(':', limit = 2).let {
                it.firstOrNull().orEmpty().trim().lowercase() to it.getOrNull(1).orEmpty().trim().lowercase()
            }
            (property == "display" && value.startsWith("none")) ||
                (property == "visibility" && value.startsWith("hidden"))
        }
    }

    private class RenderState(
        val output: TextOutput = TextOutput(),
        val lists: MutableList<ListContext> = mutableListOf(),
        val rowCellCounts: MutableList<Int> = mutableListOf(),
        val links: MutableList<LinkContext> = mutableListOf(),
    )

    private data class LinkContext(val textStart: Int, val href: String)

    private data class ListContext(val ordered: Boolean, var next: Int = 1)

    private class TextOutput {
        private val value = StringBuilder()
        private var pendingSpace = false
        private var pendingImageAlternative: String? = null

        val length: Int
            get() = value.length

        fun substring(start: Int): String = value.substring(start.coerceAtMost(value.length))

        fun text(text: String) {
            if (text.any { !it.isWhitespace() }) {
                val alternative = pendingImageAlternative
                pendingImageAlternative = null
                if (alternative != null && !startsWithPhrase(text.trimStart(), alternative)) {
                    appendText("[$alternative] ")
                }
            }
            appendText(text)
        }

        fun imageAlternative(alternative: String) {
            val normalized = alternative.trim()
            if (normalized.isEmpty()) return
            flushPendingImageAlternative()
            pendingImageAlternative = normalized
        }

        fun flushPendingImageAlternative() {
            val alternative = pendingImageAlternative ?: return
            pendingImageAlternative = null
            appendText("[$alternative] ")
        }

        private fun appendText(text: String) {
            for (char in text) {
                if (char.isWhitespace()) {
                    pendingSpace = true
                } else {
                    if (pendingSpace && value.isNotEmpty() && value.last() != '\n' && value.last() != ' ') {
                        value.append(' ')
                    }
                    value.append(char)
                    pendingSpace = false
                }
            }
        }

        fun lineBreak() {
            flushPendingImageAlternative()
            trimTrailingSpaces()
            pendingSpace = false
            if (value.isNotEmpty() && value.last() != '\n') value.append('\n')
        }

        fun blankLine() {
            lineBreak()
            if (value.isNotEmpty() && (value.length < 2 || value[value.length - 2] != '\n')) {
                value.append('\n')
            }
        }

        fun cellSeparator() {
            flushPendingImageAlternative()
            trimTrailingSpaces()
            pendingSpace = false
            if (value.isNotEmpty() && value.last() != '\n') value.append(" | ")
        }

        fun linePrefix(prefix: String) {
            pendingSpace = false
            if (value.isEmpty() || value.last() == '\n') value.append(prefix)
        }

        fun finish(): String {
            flushPendingImageAlternative()
            trimTrailingSpaces()
            return value.toString()
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }

        private fun trimTrailingSpaces() {
            while (value.isNotEmpty() && value.last() == ' ') value.deleteCharAt(value.lastIndex)
        }

        private fun startsWithPhrase(text: String, phrase: String): Boolean =
            text.startsWith(phrase, ignoreCase = true) &&
                (text.length == phrase.length || !text[phrase.length].isLetterOrDigit())
    }
}

package bosca.documents.html

import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val logger = LoggerFactory.getLogger(DocumentHtmlConverter::class.java)

/**
 * Serializes Bosca's structured JSONB document content to HTML and parses it back again.
 *
 * Document content is a nested tree shaped like:
 * ```
 * { "type": "doc", "content": [ { "type": "paragraph", "content": [ { "type": "text", "text": "..." } ] } ] }
 * ```
 * which covers the ProseMirror-flavoured schema used by Bosca's content editor.
 * This converter targets that shape; other shapes round-trip as a single `<pre>`-encoded
 * opaque block so non-translatable content is preserved on the return trip.
 *
 * Round-trip invariant: `fromHtml(toHtml(content))` preserves the visible text and the
 * set of block types; non-textual attributes are preserved as `data-*` attributes on the
 * corresponding HTML element. This is strictly good enough for external translation
 * tools (Crowdin, Weblate) that only need to surface translatable text.
 */
object DocumentHtmlConverter {

    private const val OPAQUE_MARKER = "data-bosca-opaque"
    private const val OPAQUE_HASH_ATTR = "data-bosca-hash"

    /** Renders a document [content] tree as an HTML fragment. */
    fun toHtml(content: JsonElement): String {
        val sb = StringBuilder()
        renderNode(content, sb)
        return sb.toString()
    }

    /**
     * Parses an HTML fragment produced by [toHtml] (or handcrafted in a translation tool
     * that follows the same element conventions) back into a document JSON tree.
     */
    fun fromHtml(html: String): JsonElement {
        val tokens = HtmlTokenizer.tokenize(html)
        val iterator = tokens.listIterator()
        val children = mutableListOf<JsonElement>()
        while (iterator.hasNext()) {
            val node = parseBlock(iterator) ?: continue
            if (node is JsonArray) children.addAll(node) else children.add(node)
        }
        return buildJsonObject {
            put("type", "doc")
            put("content", JsonArray(children))
        }
    }

    private fun renderNode(node: JsonElement, sb: StringBuilder) {
        if (node !is JsonObject) {
            sb.append(escape(node.toString()))
            return
        }
        val type = node["type"]?.jsonPrimitive?.content ?: "unknown"
        when (type) {
            "doc" -> renderChildren(node, sb)
            "paragraph" -> {
                sb.append("<p>")
                renderChildren(node, sb)
                sb.append("</p>")
            }
            "heading" -> {
                val level = node["attrs"]?.jsonObject?.get("level")?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 6) ?: 1
                sb.append("<h$level>")
                renderChildren(node, sb)
                sb.append("</h$level>")
            }
            "bullet_list", "bulletList" -> {
                sb.append("<ul>")
                renderChildren(node, sb)
                sb.append("</ul>")
            }
            "ordered_list", "orderedList" -> {
                sb.append("<ol>")
                renderChildren(node, sb)
                sb.append("</ol>")
            }
            "list_item", "listItem" -> {
                sb.append("<li>")
                renderChildren(node, sb)
                sb.append("</li>")
            }
            "task_list", "taskList" -> {
                sb.append("""<ul class="contains-task-list">""")
                renderChildren(node, sb)
                sb.append("</ul>")
            }
            "task_item", "taskItem" -> {
                val checked = node["attrs"]?.jsonObject?.get("checked")?.jsonPrimitive?.content == "true"
                val checkedAttr = if (checked) " checked" else ""
                sb.append("""<li class="task-list-item"><input type="checkbox" disabled$checkedAttr/>""")
                renderChildren(node, sb)
                sb.append("</li>")
            }
            "table" -> {
                sb.append("<table>")
                val rows = (node["content"] as? JsonArray).orEmpty()
                val (headerRows, bodyRows) = rows.partition { isHeaderRow(it) }
                if (headerRows.isNotEmpty()) {
                    sb.append("<thead>")
                    headerRows.forEach { renderNode(it, sb) }
                    sb.append("</thead>")
                }
                if (bodyRows.isNotEmpty()) {
                    sb.append("<tbody>")
                    bodyRows.forEach { renderNode(it, sb) }
                    sb.append("</tbody>")
                }
                sb.append("</table>")
            }
            "table_row", "tableRow" -> {
                sb.append("<tr>")
                renderChildren(node, sb)
                sb.append("</tr>")
            }
            "table_header", "tableHeader" -> {
                sb.append("<th>")
                renderCellChildren(node, sb)
                sb.append("</th>")
            }
            "table_cell", "tableCell" -> {
                sb.append("<td>")
                renderCellChildren(node, sb)
                sb.append("</td>")
            }
            "blockquote" -> {
                sb.append("<blockquote>")
                renderChildren(node, sb)
                sb.append("</blockquote>")
            }
            "code_block", "codeBlock" -> {
                val language = node["attrs"]?.jsonObject?.get("language")?.jsonPrimitive?.contentOrNull
                if (language != null) {
                    sb.append("""<pre><code class="language-${escape(language)}">""")
                } else {
                    sb.append("<pre><code>")
                }
                renderChildren(node, sb)
                sb.append("</code></pre>")
            }
            "horizontal_rule", "horizontalRule" -> sb.append("<hr/>")
            "image" -> {
                val attrs = node["attrs"] as? JsonObject
                val src = attrs?.get("src")?.jsonPrimitive?.contentOrNull.orEmpty()
                val alt = attrs?.get("alt")?.jsonPrimitive?.contentOrNull
                val title = attrs?.get("title")?.jsonPrimitive?.contentOrNull
                val metadataId = attrs?.get("metadataId")?.jsonPrimitive?.contentOrNull
                sb.append("""<img src="${escape(src)}"""")
                if (alt != null) sb.append(""" alt="${escape(alt)}"""")
                if (title != null) sb.append(""" title="${escape(title)}"""")
                if (metadataId != null) sb.append(""" data-metadata-id="${escape(metadataId)}"""")
                sb.append("/>")
            }
            "text" -> {
                val text = node["text"]?.jsonPrimitive?.content.orEmpty()
                val marks = node["marks"] as? JsonArray
                val openTags = StringBuilder()
                val closeTags = StringBuilder()
                marks?.forEach { mark ->
                    val markObj = mark as? JsonObject ?: return@forEach
                    val markType = markObj["type"]?.jsonPrimitive?.content ?: return@forEach
                    when (markType) {
                        "bold" -> { openTags.append("<b>"); closeTags.insert(0, "</b>") }
                        "italic" -> { openTags.append("<em>"); closeTags.insert(0, "</em>") }
                        "underline" -> { openTags.append("<u>"); closeTags.insert(0, "</u>") }
                        "strike" -> { openTags.append("<del>"); closeTags.insert(0, "</del>") }
                        "code" -> { openTags.append("<code>"); closeTags.insert(0, "</code>") }
                        "link" -> {
                            val href = markObj["attrs"]?.jsonObject?.get("href")?.jsonPrimitive?.content.orEmpty()
                            openTags.append("""<a href="${escape(href)}">""")
                            closeTags.insert(0, "</a>")
                        }
                    }
                }
                sb.append(openTags)
                sb.append(escape(text))
                sb.append(closeTags)
            }
            "hard_break", "hardBreak" -> sb.append("<br/>")
            else -> {
                val raw = node.toString()
                val hash = opaqueHash(raw)
                val encoded = escape(raw)
                sb.append("""<div $OPAQUE_MARKER="true" $OPAQUE_HASH_ATTR="$hash">$encoded</div>""")
            }
        }
    }

    private fun renderChildren(node: JsonObject, sb: StringBuilder) {
        val children = node["content"] as? JsonArray ?: return
        children.forEach { renderNode(it, sb) }
    }

    /**
     * Renders the children of a table cell. TipTap wraps cell content in a paragraph
     * but a CommonMark-parseable HTML table expects bare inline content inside `<th>`/`<td>`.
     * For the common case of a single paragraph child, this unwraps it; otherwise the
     * children are rendered as-is.
     */
    private fun renderCellChildren(node: JsonObject, sb: StringBuilder) {
        val children = node["content"] as? JsonArray ?: return
        if (children.size == 1) {
            val only = children[0] as? JsonObject
            if (only != null && only["type"]?.jsonPrimitive?.contentOrNull == "paragraph") {
                renderChildren(only, sb)
                return
            }
        }
        children.forEach { renderNode(it, sb) }
    }

    /** A row is a header row if every cell is a tableHeader. */
    private fun isHeaderRow(row: JsonElement): Boolean {
        val obj = row as? JsonObject ?: return false
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type != "tableRow" && type != "table_row") return false
        val cells = obj["content"] as? JsonArray ?: return false
        if (cells.isEmpty()) return false
        return cells.all { c ->
            val ct = (c as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
            ct == "tableHeader" || ct == "table_header"
        }
    }

    private fun parseBlock(iterator: ListIterator<HtmlToken>): JsonElement? {
        while (iterator.hasNext()) {
            val token = iterator.next()
            when (token) {
                is HtmlToken.OpenTag -> return parseOpenTag(token, iterator)
                is HtmlToken.SelfClosingTag -> return parseSelfClosingTag(token)
                is HtmlToken.Text -> {
                    val trimmed = token.text.trim()
                    if (trimmed.isEmpty()) continue
                    return buildJsonObject {
                        put("type", "paragraph")
                        put("content", buildJsonArray { add(textNode(trimmed)) })
                    }
                }
                is HtmlToken.CloseTag -> return null
            }
        }
        return null
    }

    private fun parseOpenTag(open: HtmlToken.OpenTag, iterator: ListIterator<HtmlToken>): JsonElement {
        if (open.attributes[OPAQUE_MARKER] == "true") {
            val expectedHash = open.attributes[OPAQUE_HASH_ATTR]
            val inner = StringBuilder()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (next is HtmlToken.CloseTag && next.name.equals(open.name, ignoreCase = true)) break
                when (next) {
                    is HtmlToken.Text -> inner.append(next.text)
                    else -> inner.append(next.toString())
                }
            }
            val unescaped = unescape(inner.toString())
            if (expectedHash != null && opaqueHash(unescaped) != expectedHash) {
                logger.warn("Opaque block hash mismatch (expected={}), content was modified by external tool", expectedHash)
                return buildJsonObject { put("type", "paragraph"); put("content", buildJsonArray { add(textNode(inner.toString())) }) }
            }
            return try {
                kotlinx.serialization.json.Json.parseToJsonElement(unescaped)
            } catch (e: SerializationException) {
                logger.warn("Failed to parse opaque block content (hash={})", expectedHash, e)
                buildJsonObject { put("type", "paragraph"); put("content", buildJsonArray { add(textNode(inner.toString())) }) }
            } catch (e: IllegalArgumentException) {
                logger.warn("Failed to parse opaque block content (hash={})", expectedHash, e)
                buildJsonObject { put("type", "paragraph"); put("content", buildJsonArray { add(textNode(inner.toString())) }) }
            }
        }
        val tag = open.name.lowercase()
        if (tag in voidElements) {
            return parseSelfClosingTag(HtmlToken.SelfClosingTag(open.name, open.attributes))
        }
        val children = mutableListOf<JsonElement>()
        while (iterator.hasNext()) {
            val next = iterator.next()
            if (next is HtmlToken.CloseTag && next.name.equals(open.name, ignoreCase = true)) break
            iterator.previous()
            val child = parseInline(iterator) ?: break
            if (child is JsonArray) children.addAll(child) else children.add(child)
        }
        val cleaned = if (tag in blockContainers || tag in transparentTags) {
            stripWhitespaceText(children)
        } else children
        if (tag in transparentTags) {
            return JsonArray(cleaned)
        }
        return blockNode(tag, open.attributes, cleaned)
    }

    private val inlineMarkTags = setOf("b", "strong", "em", "i", "code", "a", "del", "s", "strike", "u")

    private fun parseInline(iterator: ListIterator<HtmlToken>): JsonElement? {
        if (!iterator.hasNext()) return null
        return when (val token = iterator.next()) {
            is HtmlToken.Text -> textNode(unescape(token.text))
            is HtmlToken.SelfClosingTag -> parseSelfClosingTag(token)
            is HtmlToken.OpenTag -> {
                val tag = token.name.lowercase()
                if (tag in inlineMarkTags) {
                    val mark = markFromTag(tag, token.attributes)
                    val innerChildren = mutableListOf<JsonElement>()
                    while (iterator.hasNext()) {
                        val next = iterator.next()
                        if (next is HtmlToken.CloseTag && next.name.equals(token.name, ignoreCase = true)) break
                        iterator.previous()
                        val child = parseInline(iterator) ?: break
                        innerChildren.add(child)
                    }
                    val marked = applyMarkToChildren(mark, innerChildren)
                    if (marked.size == 1) marked[0] else JsonArray(marked)
                } else if (tag in voidElements) {
                    parseSelfClosingTag(HtmlToken.SelfClosingTag(token.name, token.attributes))
                } else {
                    val children = mutableListOf<JsonElement>()
                    while (iterator.hasNext()) {
                        val next = iterator.next()
                        if (next is HtmlToken.CloseTag && next.name.equals(token.name, ignoreCase = true)) break
                        iterator.previous()
                        val child = parseInline(iterator) ?: break
                        if (child is JsonArray) children.addAll(child) else children.add(child)
                    }
                    val cleaned = if (tag in blockContainers || tag in transparentTags) {
                        stripWhitespaceText(children)
                    } else children
                    if (tag in transparentTags) JsonArray(cleaned) else blockNode(tag, token.attributes, cleaned)
                }
            }
            is HtmlToken.CloseTag -> {
                iterator.previous()
                null
            }
        }
    }

    private fun markFromTag(tag: String, attrs: Map<String, String>): JsonObject = when (tag) {
        "b", "strong" -> buildJsonObject { put("type", "bold") }
        "em", "i" -> buildJsonObject { put("type", "italic") }
        "u" -> buildJsonObject { put("type", "underline") }
        "del", "s", "strike" -> buildJsonObject { put("type", "strike") }
        "code" -> buildJsonObject {
            put("type", "code")
            val lang = attrs["class"]?.split(Regex("\\s+"))
                ?.firstOrNull { it.startsWith("language-") }
                ?.removePrefix("language-")
                ?.takeIf { it.isNotBlank() }
            if (lang != null) {
                put("attrs", buildJsonObject { put("language", lang) })
            }
        }
        "a" -> buildJsonObject {
            put("type", "link")
            put("attrs", buildJsonObject {
                attrs["href"]?.let { href -> sanitizeUrl(href)?.let { put("href", it) } }
                attrs["title"]?.let { put("title", it) }
            })
        }
        else -> buildJsonObject { put("type", tag) }
    }

    /**
     * Allowlist of URL schemes that may appear in `href`/`src` attributes. Anything else
     * (notably `javascript:`, `vbscript:`, `data:`, `file:`) is dropped — leaving the
     * link/image with no URL — so a hostile markdown input cannot smuggle script-bearing
     * URLs through to a downstream renderer that doesn't sanitize. Relative URLs and
     * fragment-only URLs (`#section`) are allowed because they have no scheme.
     */
    private val allowedUrlSchemes = setOf("http", "https", "mailto", "tel", "ftp", "ftps")

    internal fun sanitizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Relative or fragment-only URLs have no scheme — keep them.
        val schemeMatch = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(trimmed) ?: return trimmed
        val scheme = schemeMatch.groupValues[1].lowercase()
        if (scheme !in allowedUrlSchemes) {
            logger.warn("Dropping URL with disallowed scheme `{}` from document content", scheme)
            return null
        }
        return trimmed
    }

    private fun applyMarkToChildren(mark: JsonObject, children: List<JsonElement>): List<JsonElement> {
        if (children.isEmpty()) {
            return listOf(buildJsonObject {
                put("type", "text")
                put("text", "")
                put("marks", JsonArray(listOf(mark)))
            })
        }
        return children.map { child ->
            if (child is JsonObject && child["type"]?.jsonPrimitive?.content == "text") {
                val existingMarks = child["marks"] as? JsonArray ?: JsonArray(emptyList())
                buildJsonObject {
                    put("type", "text")
                    put("text", child["text"]!!)
                    put("marks", JsonArray(existingMarks + mark))
                }
            } else child
        }
    }

    private fun parseSelfClosingTag(token: HtmlToken.SelfClosingTag): JsonElement = when (token.name.lowercase()) {
        "br" -> buildJsonObject { put("type", "hardBreak") }
        "hr" -> buildJsonObject { put("type", "horizontalRule") }
        "img" -> buildJsonObject {
            put("type", "image")
            put("attrs", buildJsonObject {
                token.attributes["src"]?.let { src -> sanitizeUrl(src)?.let { put("src", it) } }
                token.attributes["alt"]?.let { put("alt", it) }
                token.attributes["title"]?.let { put("title", it) }
                token.attributes["data-metadata-id"]?.let { put("metadataId", it) }
            })
        }
        "input" -> {
            // Input checkboxes inside task list items are handled by parseOpenTag for <li class="task-list-item">.
            // Self-closing inputs that escape into the doc become opaque pass-throughs.
            buildJsonObject {
                put("type", "input")
                if (token.attributes.isNotEmpty()) {
                    put("attrs", buildJsonObject {
                        token.attributes.forEach { (k, v) -> put(k, v) }
                    })
                }
            }
        }
        else -> buildJsonObject {
            put("type", token.name.lowercase())
            if (token.attributes.isNotEmpty()) {
                put("attrs", buildJsonObject {
                    token.attributes.forEach { (k, v) -> put(k, v) }
                })
            }
        }
    }

    private fun blockNode(tag: String, attrs: Map<String, String>, children: List<JsonElement>): JsonObject {
        val classes = attrs["class"]?.split(Regex("\\s+"))?.toSet().orEmpty()
        // CommonMark's task-list extension emits a bare `<input>` as the first child of `<li>`
        // without the GFM "task-list-item"/"contains-task-list" classes. Recognize either form.
        val hasInputChild = children.any { c ->
            (c as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "input"
        }
        val isTaskItem = tag == "li" && ("task-list-item" in classes || hasInputChild)
        val allChildrenTaskItems = children.isNotEmpty() && children.all { c ->
            (c as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "taskItem"
        }
        val isTaskList = tag == "ul" && ("contains-task-list" in classes || allChildrenTaskItems)

        val type = when {
            tag == "p" -> "paragraph"
            tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> "heading"
            isTaskList -> "taskList"
            tag == "ul" -> "bulletList"
            tag == "ol" -> "orderedList"
            isTaskItem -> "taskItem"
            tag == "li" -> "listItem"
            tag == "blockquote" -> "blockquote"
            tag == "pre" -> "codeBlock"
            tag == "hr" -> "horizontalRule"
            tag == "table" -> "table"
            tag == "tr" -> "tableRow"
            tag == "th" -> "tableHeader"
            tag == "td" -> "tableCell"
            else -> tag
        }

        // Code blocks: parser turns <code> into a code mark on text nodes. Strip the code mark
        // (codeBlock content is plain text) and hoist any language out of the mark's attrs.
        if (type == "codeBlock") {
            var language: String? = null
            val cleaned = children.map { c ->
                if (c is JsonObject && c["type"]?.jsonPrimitive?.contentOrNull == "text") {
                    val marks = c["marks"] as? JsonArray
                    val codeMark = marks?.firstOrNull { m ->
                        (m as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "code"
                    } as? JsonObject
                    if (codeMark != null && language == null) {
                        language = (codeMark["attrs"] as? JsonObject)?.get("language")?.jsonPrimitive?.contentOrNull
                    }
                    val keptMarks = marks?.filter { m ->
                        (m as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull != "code"
                    } ?: emptyList()
                    buildJsonObject {
                        put("type", "text")
                        c["text"]?.let { put("text", it) }
                        if (keptMarks.isNotEmpty()) put("marks", JsonArray(keptMarks))
                    }
                } else c
            }
            return buildJsonObject {
                put("type", "codeBlock")
                language?.let { lang ->
                    put("attrs", buildJsonObject { put("language", lang) })
                }
                put("content", JsonArray(cleaned))
            }
        }

        // Task items: drop the leading <input> child, hoist its checked state into attrs.checked.
        if (type == "taskItem") {
            val checked = children
                .filterIsInstance<JsonObject>()
                .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "input" }
                ?.let { it["attrs"] as? JsonObject }
                ?.containsKey("checked") ?: false
            val itemChildren = trimLeadingTextWhitespace(children.filter { c ->
                (c as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull != "input"
            })
            return buildJsonObject {
                put("type", "taskItem")
                put("attrs", buildJsonObject { put("checked", checked) })
                put("content", JsonArray(wrapBlockChildrenIfInline(itemChildren)))
            }
        }

        // Table cells/headers and list items expect block-level children (paragraphs).
        // CommonMark output gives them bare inline content, so wrap if needed.
        if (type == "tableCell" || type == "tableHeader" || type == "listItem") {
            return buildJsonObject {
                put("type", type)
                val carriedAttrs = attrs.filterKeys { it != OPAQUE_MARKER && it != "class" }
                if (carriedAttrs.isNotEmpty()) {
                    put("attrs", buildJsonObject {
                        carriedAttrs.forEach { (k, v) -> put(k, v) }
                    })
                }
                put("content", JsonArray(wrapBlockChildrenIfInline(children)))
            }
        }

        return buildJsonObject {
            put("type", type)
            if (type == "heading") {
                put("attrs", buildJsonObject { put("level", (tag[1] - '0')) })
            } else {
                val carriedAttrs = attrs.filterKeys { it != OPAQUE_MARKER && it != "class" }
                if (carriedAttrs.isNotEmpty()) {
                    put("attrs", buildJsonObject {
                        carriedAttrs.forEach { (k, v) -> put(k, v) }
                    })
                }
            }
            put("content", JsonArray(children))
        }
    }

    private fun textNode(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private val inlineTypes = setOf("text", "hardBreak", "hard_break")
    private val transparentTags = setOf("thead", "tbody", "tfoot")

    /**
     * HTML void elements (no closing tag). CommonMark emits `<input ...>` without a slash,
     * so the tokenizer reports them as OpenTag; we must not absorb subsequent siblings as
     * children.
     */
    private val voidElements = setOf("input", "img", "br", "hr", "meta", "link", "source", "area", "col", "embed")

    /**
     * Tags whose direct children must be other blocks. Whitespace text between those blocks
     * (e.g. the newlines CommonMark inserts between `<li>` siblings) is not meaningful and
     * would otherwise pollute the parsed tree with stray text nodes.
     */
    private val blockContainers = setOf(
        "ul", "ol", "li", "table", "tr", "thead", "tbody", "tfoot",
        "th", "td", "blockquote", "div"
    )

    private fun stripWhitespaceText(children: List<JsonElement>): List<JsonElement> = children.filter { c ->
        val obj = c as? JsonObject ?: return@filter true
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "text") return@filter true
        obj["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() ?: false
    }

    /**
     * Removes a single leading space from the first text child if present. CommonMark's
     * task-list extension renders `[x] foo` as `<input>... foo`, leaving the visual gap
     * between the checkbox and the label as content; we don't want that in the model.
     */
    private fun trimLeadingTextWhitespace(children: List<JsonElement>): List<JsonElement> {
        if (children.isEmpty()) return children
        val first = children.first() as? JsonObject ?: return children
        if (first["type"]?.jsonPrimitive?.contentOrNull != "text") return children
        val text = first["text"]?.jsonPrimitive?.contentOrNull ?: return children
        val trimmed = text.trimStart()
        if (trimmed == text) return children
        val replaced = buildJsonObject {
            put("type", "text")
            put("text", trimmed)
            first["marks"]?.let { put("marks", it) }
        }
        return listOf(replaced) + children.drop(1)
    }

    private fun wrapBlockChildrenIfInline(children: List<JsonElement>): List<JsonElement> {
        if (children.isEmpty()) return children
        // Normalize each consecutive run of inline children into a paragraph so the
        // resulting JSON shape doesn't depend on whether CommonMark emitted a tight
        // (`<li>x</li>`) or loose (`<li><p>x</p></li>`) list. Without this, round-trips
        // through the markdown converter drift between iterations.
        val normalized = mutableListOf<JsonElement>()
        var inlineRun = mutableListOf<JsonElement>()
        fun flush() {
            if (inlineRun.isEmpty()) return
            // Strip leading/trailing whitespace from boundary text nodes — `<li>foo\n<ul>...`
            // leaves a meaningless newline that would otherwise re-emerge as a blank line.
            val trimmed = trimEdgeTextWhitespace(inlineRun)
            if (trimmed.isNotEmpty()) {
                normalized.add(buildJsonObject {
                    put("type", "paragraph")
                    put("content", JsonArray(trimmed))
                })
            }
            inlineRun = mutableListOf()
        }
        for (c in children) {
            val type = (c as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
            if (type != null && type in inlineTypes) {
                inlineRun.add(c)
            } else {
                flush()
                normalized.add(c)
            }
        }
        flush()
        return normalized
    }

    private fun trimEdgeTextWhitespace(nodes: List<JsonElement>): List<JsonElement> {
        if (nodes.isEmpty()) return nodes
        val list = nodes.toMutableList()
        // Trim the first text node's leading whitespace.
        (list.firstOrNull() as? JsonObject)?.let { first ->
            if (first["type"]?.jsonPrimitive?.contentOrNull == "text") {
                val text = first["text"]?.jsonPrimitive?.contentOrNull ?: return@let
                val newText = text.trimStart()
                list[0] = if (newText.isEmpty()) JsonObject(emptyMap()) else buildJsonObject {
                    put("type", "text")
                    put("text", newText)
                    first["marks"]?.let { put("marks", it) }
                }
            }
        }
        // Trim the last text node's trailing whitespace.
        (list.lastOrNull() as? JsonObject)?.let { last ->
            if (last["type"]?.jsonPrimitive?.contentOrNull == "text") {
                val text = last["text"]?.jsonPrimitive?.contentOrNull ?: return@let
                val newText = text.trimEnd()
                list[list.lastIndex] = if (newText.isEmpty()) JsonObject(emptyMap()) else buildJsonObject {
                    put("type", "text")
                    put("text", newText)
                    last["marks"]?.let { put("marks", it) }
                }
            }
        }
        // Drop any nodes that became empty placeholders after trimming.
        return list.filter { (it as? JsonObject)?.isNotEmpty() ?: true }
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescape(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&")

    @OptIn(ExperimentalStdlibApi::class)
    private fun opaqueHash(content: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray(Charsets.UTF_8)).toHexString().take(16)
    }
}

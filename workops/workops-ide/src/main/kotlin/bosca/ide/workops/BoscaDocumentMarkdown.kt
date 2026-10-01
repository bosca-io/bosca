package bosca.ide.workops

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Read-side conversion for the common TipTap nodes used by WorkOps documents. Saves use the server's converter. */
internal object BoscaDocumentMarkdown {
    fun toMarkdown(content: JsonElement?): String {
        if (content == null || content.isJsonNull) return ""
        val root = content.asJsonObject.let { it.getAsJsonObject("document") ?: it }
        return buildString { renderBlock(root, this, "") }.trimEnd()
    }

    private fun renderBlock(node: JsonObject, output: StringBuilder, indent: String) {
        when (node.string("type")) {
            "doc" -> node.children().forEach { renderBlock(it, output, indent) }
            "heading" -> {
                output.append("#".repeat(node.attrs().int("level", 1))).append(' ')
                renderInlineChildren(node, output)
                output.append("\n\n")
            }
            "paragraph" -> {
                renderInlineChildren(node, output)
                output.append("\n\n")
            }
            "bulletList", "bullet_list" -> renderList(node, output, indent, ordered = false)
            "orderedList", "ordered_list" -> renderList(node, output, indent, ordered = true)
            "taskList", "task_list" -> renderTaskList(node, output, indent)
            "blockquote" -> {
                val nested = buildString { node.children().forEach { renderBlock(it, this, indent) } }.trimEnd()
                nested.lines().forEach { output.append("> ").append(it).append('\n') }
                output.append('\n')
            }
            "horizontalRule", "horizontal_rule" -> output.append("---\n\n")
            "codeBlock", "code_block" -> {
                output.append("```").append(node.attrs().string("language").orEmpty()).append('\n')
                node.children().forEach { output.append(it.string("text").orEmpty()) }
                if (!output.endsWith("\n")) output.append('\n')
                output.append("```\n\n")
            }
            else -> node.children().forEach { renderBlock(it, output, indent) }
        }
    }

    private fun renderList(node: JsonObject, output: StringBuilder, indent: String, ordered: Boolean) {
        node.children().forEachIndexed { index, item ->
            output.append(indent).append(if (ordered) "${index + 1}. " else "- ")
            renderListItem(item, output, "$indent  ")
        }
        if (indent.isEmpty()) output.append('\n')
    }

    private fun renderTaskList(node: JsonObject, output: StringBuilder, indent: String) {
        node.children().forEach { item ->
            output.append(indent).append(if (item.attrs().boolean("checked")) "- [x] " else "- [ ] ")
            renderListItem(item, output, "$indent  ")
        }
        if (indent.isEmpty()) output.append('\n')
    }

    private fun renderListItem(item: JsonObject, output: StringBuilder, nestedIndent: String) {
        var first = true
        item.children().forEach { child ->
            when (child.string("type")) {
                "paragraph" -> {
                    if (!first) output.append(nestedIndent)
                    renderInlineChildren(child, output)
                    output.append('\n')
                }
                else -> renderBlock(child, output, nestedIndent)
            }
            first = false
        }
        if (first) output.append('\n')
    }

    private fun renderInlineChildren(node: JsonObject, output: StringBuilder) {
        node.children().forEach { renderInline(it, output) }
    }

    private fun renderInline(node: JsonObject, output: StringBuilder) {
        when (node.string("type")) {
            "text" -> {
                var value = node.string("text").orEmpty()
                node.getAsJsonArray("marks")?.map { it.asJsonObject }?.forEach { mark ->
                    value = when (mark.string("type")) {
                        "bold", "strong" -> "**$value**"
                        "italic", "em" -> "*$value*"
                        "strike" -> "~~$value~~"
                        "code" -> "`$value`"
                        "link" -> "[$value](${mark.attrs().string("href").orEmpty()})"
                        else -> value
                    }
                }
                output.append(value)
            }
            "hardBreak", "hard_break" -> output.append("  \n")
            "mention" -> output.append('@').append(node.attrs().string("label") ?: node.attrs().string("id").orEmpty())
            "image" -> output.append("![")
                .append(node.attrs().string("alt").orEmpty())
                .append("](").append(node.attrs().string("src").orEmpty()).append(')')
            else -> renderInlineChildren(node, output)
        }
    }

    private fun JsonObject.children(): List<JsonObject> =
        getAsJsonArray("content")?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()

    private fun JsonObject.attrs(): JsonObject = getAsJsonObject("attrs") ?: JsonObject()
    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.int(name: String, fallback: Int): Int = get(name)?.takeUnless { it.isJsonNull }?.asInt ?: fallback
    private fun JsonObject.boolean(name: String): Boolean = get(name)?.takeUnless { it.isJsonNull }?.asBoolean == true
    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()
}

package bosca.bible.processor

import bosca.bible.usx.Item
import bosca.bible.usx.ItemContainer
import bosca.bible.usx.Usx
import bosca.bible.usx.VerseItems

class HtmlContext(
    val pretty: Boolean,
    val includeFootNotes: Boolean,
    val includeCrossReferences: Boolean,
    val includeVerseNumbers: Boolean,
    private var indent: Int = 0
) {

    fun addIndent() {
        this.indent += 2
    }

    fun removeIndent() {
        this.indent -= 2
    }

    fun render(tag: String, item: Usx, text: String? = null, forEach: ((items: List<Item>) -> String)? = null): String {
        val defaultForEach = { items: List<Item> ->
            var childHtml = ""
            for (child in items) {
                childHtml += child.toHtml(this)
            }
            childHtml
        }

        var html = ""
        if (this.pretty) html += " ".repeat(this.indent)
        html += "<$tag"
        val attrs = item.htmlAttributes.toMutableMap()
        if (item.htmlClass != "") attrs["class"] = item.htmlClass
        for ((key, value) in attrs.entries) {
            html += " $key=\"$value\""
        }
        html += ">"
        if (this.pretty) html += "\n"
        this.addIndent()
        var childHtml = ""
        if (text != null) {
            if (this.pretty) html += " ".repeat(this.indent)
            childHtml += text
        } else if (item is ItemContainer<*>) {
            childHtml += (forEach ?: defaultForEach).invoke(item.items)
        } else if (item is VerseItems) {
            childHtml += (forEach ?: defaultForEach).invoke(item.items)
        } else {
            childHtml += item.toHtml(this)
        }
        html += childHtml
        if (this.pretty && !html.endsWith("\n")) html += "\n"
        this.removeIndent()
        if (this.pretty) html += " ".repeat(this.indent)
        html += "</$tag>"
        if (this.pretty) html += "\n"
        return html
    }
}
package bosca.documents.html

/**
 * Minimal HTML tokenizer sufficient for round-tripping
 * [DocumentHtmlConverter] output.
 *
 * Deliberately narrow: this is not an HTML5 parser. It handles the tag shapes the
 * converter emits (well-formed open, close, self-closing, and text runs) and does not
 * attempt to cope with malformed or browser-only HTML. That is acceptable because
 * the converter is bidirectional against its own output; translation tools that
 * transform the intermediate HTML only touch text between tags.
 */
internal sealed interface HtmlToken {
    data class OpenTag(val name: String, val attributes: Map<String, String>) : HtmlToken
    data class CloseTag(val name: String) : HtmlToken
    data class SelfClosingTag(val name: String, val attributes: Map<String, String>) : HtmlToken
    data class Text(val text: String) : HtmlToken
}

internal object HtmlTokenizer {

    fun tokenize(source: String): List<HtmlToken> {
        val tokens = mutableListOf<HtmlToken>()
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            if (ch == '<') {
                val end = source.indexOf('>', i)
                if (end < 0) {
                    tokens.add(HtmlToken.Text(source.substring(i)))
                    break
                }
                val raw = source.substring(i + 1, end).trim()
                i = end + 1
                tokens.add(parseTag(raw))
            } else {
                val next = source.indexOf('<', i)
                val slice = if (next < 0) source.substring(i) else source.substring(i, next)
                tokens.add(HtmlToken.Text(slice))
                i = if (next < 0) source.length else next
            }
        }
        return tokens
    }

    private fun parseTag(raw: String): HtmlToken {
        if (raw.startsWith("/")) {
            return HtmlToken.CloseTag(raw.substring(1).trim().substringBefore(' '))
        }
        val selfClosing = raw.endsWith("/")
        val body = if (selfClosing) raw.dropLast(1).trim() else raw
        val parts = body.split(Regex("""\s+"""), limit = 2)
        val name = parts[0]
        val attrs = if (parts.size > 1) parseAttributes(parts[1]) else emptyMap()
        return if (selfClosing) HtmlToken.SelfClosingTag(name, attrs) else HtmlToken.OpenTag(name, attrs)
    }

    private fun parseAttributes(source: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val pattern = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*(?:=\s*("([^"]*)"|'([^']*)'|([^\s>]+)))?""")
        for (match in pattern.findAll(source)) {
            val key = match.groupValues[1]
            val value = match.groupValues
                .drop(3)
                .firstOrNull { it.isNotEmpty() }
                ?: ""
            result[key] = value
        }
        return result
    }
}

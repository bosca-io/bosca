package bosca.bml.render

/** HTML escaping for BML output. */
object Html {

    /** Escape text content / attribute values for safe HTML output. */
    fun escape(value: String): String {
        if (value.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return value
        return buildString(value.length + 16) { appendEscapedCharacters(this, value) }
    }

    /** Write escaped content into an existing output buffer without creating an intermediate string. */
    internal fun appendEscaped(out: StringBuilder, value: String) {
        if (value.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) {
            out.append(value)
            return
        }
        appendEscapedCharacters(out, value)
    }

    private fun appendEscapedCharacters(out: StringBuilder, value: String) {
        for (c in value) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(c)
            }
        }
    }

    /** Escape a value destined for a double-quoted attribute. */
    fun escapeAttribute(value: String): String = escape(value)
}

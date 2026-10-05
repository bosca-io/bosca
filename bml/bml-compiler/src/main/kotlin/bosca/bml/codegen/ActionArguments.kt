package bosca.bml.codegen

/** Splits a Kotlin/TypeScript action argument list without splitting nested literals or calls. */
internal fun splitActionArguments(source: String): List<String> {
    if (source.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    var start = 0
    var depth = 0
    var quote: Char? = null
    var escaped = false
    source.forEachIndexed { index, char ->
        if (quote != null) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == quote) quote = null
        } else {
            when (char) {
                '\'', '"', '`' -> quote = char
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    result += source.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
    }
    result += source.substring(start).trim()
    return result
}

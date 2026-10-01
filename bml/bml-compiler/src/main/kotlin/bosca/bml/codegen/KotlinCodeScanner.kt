package bosca.bml.codegen

/**
 * Reduces embedded Kotlin to the text that can execute, so compile-time checks can look for an API
 * access without matching prose. Comments (including nested block comments), string and character
 * literal contents are replaced by spaces; `${…}` string-template expressions are kept because they
 * are evaluated. The scanner is lexical, not a parser: unterminated input is consumed to the end.
 */
internal class KotlinCodeScanner(private val source: String) {
    private val out = StringBuilder(source.length)
    private var index = 0

    fun codeWithoutLiterals(): String {
        index = 0
        out.setLength(0)
        code(stopAtClosingBrace = false)
        return out.toString()
    }

    /** Copies code until the end of input, or until an unmatched `}` that closes a `${` template. */
    private fun code(stopAtClosingBrace: Boolean) {
        var depth = 0
        while (index < source.length) {
            val char = source[index]
            when {
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index)
                    index = if (end < 0) source.length else end
                    out.append(' ')
                }
                source.startsWith("/*", index) -> blockComment()
                source.startsWith("\"\"\"", index) -> {
                    index += 3
                    string(raw = true)
                }
                char == '"' -> {
                    index++
                    string(raw = false)
                }
                char == '\'' -> characterLiteral()
                char == '{' -> {
                    depth++
                    out.append(char)
                    index++
                }
                char == '}' -> {
                    index++
                    if (stopAtClosingBrace && depth == 0) return
                    depth--
                    out.append(char)
                }
                else -> {
                    out.append(char)
                    index++
                }
            }
        }
    }

    private fun blockComment() {
        var depth = 0
        while (index < source.length) {
            when {
                source.startsWith("/*", index) -> {
                    depth++
                    index += 2
                }
                source.startsWith("*/", index) -> {
                    depth--
                    index += 2
                    if (depth == 0) break
                }
                else -> index++
            }
        }
        out.append(' ')
    }

    private fun string(raw: Boolean) {
        out.append(' ')
        while (index < source.length) {
            when {
                raw && source.startsWith("\"\"\"", index) -> {
                    // A raw string may end with extra quote characters: `""""` closes after one quote.
                    while (index < source.length && source[index] == '"') index++
                    break
                }
                !raw && source[index] == '"' -> {
                    index++
                    break
                }
                !raw && source[index] == '\\' -> index += 2
                source.startsWith("\${", index) -> {
                    index += 2
                    out.append(' ')
                    code(stopAtClosingBrace = true)
                    out.append(' ')
                }
                else -> index++
            }
        }
        out.append(' ')
    }

    private fun characterLiteral() {
        index++
        if (index < source.length && source[index] == '\\') index += 2
        while (index < source.length && source[index] != '\'') index++
        index++
        out.append(' ')
    }
}

package bosca.bml.codegen

/**
 * Literal static ESM imports and re-exports, excluding comments, strings, regexps and dynamic imports.
 * Uses the client lexical scanner rather than evaluating downloaded code. Import maps and runtime
 * loading remain the caller's responsibility.
 */
fun clientModuleImports(source: String): List<String> {
    val declarations = Regex("""(?<![\w$.])(?:import\s*(?:[^;"'()`]*?\bfrom\s*)?|export\s*(?:\*\s*(?:as\s+[\w$]+\s*)?|\{[^}]*})\s*from\s*)(["'])""")
    return declarations.findAll(ClientScriptScanner(source).maskNonCode()).mapNotNull { match ->
        val quote = source[match.range.last]
        val value = StringBuilder()
        var index = match.range.last + 1
        while (index < source.length) {
            val char = source[index++]
            if (char == quote) return@mapNotNull value.toString()
            if (char != '\\') { value.append(char); continue }
            if (index == source.length) return@mapNotNull null
            when (val escaped = source[index++]) {
                '\n' -> Unit
                '\r' -> if (source.getOrNull(index) == '\n') index++
                'n' -> value.append('\n')
                'r' -> value.append('\r')
                't' -> value.append('\t')
                'b' -> value.append('\b')
                'f' -> value.append('\u000C')
                'v' -> value.append('\u000B')
                '0' -> value.append('\u0000')
                'x', 'u' -> {
                    val braced = escaped == 'u' && source.getOrNull(index) == '{'
                    if (braced) index++
                    val end = if (braced) source.indexOf('}', index) else index + if (escaped == 'x') 2 else 4
                    if (end < index || end > source.length) return@mapNotNull null
                    val point = source.substring(index, end).toIntOrNull(16) ?: return@mapNotNull null
                    if (point !in 0..0x10FFFF) return@mapNotNull null
                    value.appendCodePoint(point)
                    index = end + if (braced) 1 else 0
                }
                else -> value.append(escaped)
            }
        }
        null
    }.distinct().toList()
}

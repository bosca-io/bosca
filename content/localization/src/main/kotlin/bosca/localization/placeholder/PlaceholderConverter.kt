package bosca.localization.placeholder

/**
 * Converts ICU-style placeholders into the syntax expected by a target export format.
 *
 * ICU is the canonical internal representation; this converter is invoked from the
 * exporters that target Android/iOS resource files, which use positional
 * format specifiers instead.
 */
object PlaceholderConverter {

    private data class IcuPlaceholder(val name: String, val type: String, val fullMatch: String, val range: IntRange)

    private fun findTopLevelPlaceholders(source: String): List<IcuPlaceholder> {
        val results = mutableListOf<IcuPlaceholder>()
        var i = 0
        while (i < source.length) {
            if (source[i] == '{') {
                var depth = 1
                val start = i
                i++
                while (i < source.length && depth > 0) {
                    if (source[i] == '{') depth++
                    else if (source[i] == '}') depth--
                    i++
                }
                if (depth == 0) {
                    val inner = source.substring(start + 1, i - 1).trim()
                    val parts = inner.split(',', limit = 3).map { it.trim() }
                    val name = parts[0].takeIf { it.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")) } ?: continue
                    val type = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "string"
                    results.add(IcuPlaceholder(name, type, source.substring(start, i), start until i))
                }
            } else {
                i++
            }
        }
        return results
    }

    /**
     * Rewrites ICU placeholders in [source] into Android-style positional specifiers:
     * string arguments become `%1$s`, number/int arguments become `%1$d`, and
     * date/time/currency arguments fall back to `%1$s`.
     *
     * Positional indices assign in first-seen order and persist across repeat uses of
     * the same placeholder name, matching Android's expectation that `%1$s` refers to
     * the first argument every time it appears.
     */
    fun toAndroid(source: String): String {
        val placeholders = findTopLevelPlaceholders(source)
        if (placeholders.isEmpty()) return source
        val order = linkedMapOf<String, Pair<Int, String>>()
        var nextIndex = 1
        for (ph in placeholders) {
            if (!order.containsKey(ph.name)) {
                order[ph.name] = nextIndex++ to ph.type
            }
        }
        val sb = StringBuilder()
        var lastEnd = 0
        for (ph in placeholders) {
            sb.append(source, lastEnd, ph.range.first)
            val (index, type) = order.getValue(ph.name)
            val specifier = when (type.lowercase()) {
                "number", "integer", "int" -> "d"
                "double", "float" -> "f"
                else -> "s"
            }
            sb.append("%$index\$$specifier")
            lastEnd = ph.range.last + 1
        }
        sb.append(source, lastEnd, source.length)
        return sb.toString()
    }

    /**
     * Rewrites ICU placeholders in [source] into iOS-style `%@` object specifiers.
     * iOS's Foundation formatter does not use positional indices the way Android does,
     * so every placeholder becomes `%@` in order. Callers that need numeric types
     * (`%d`, `%f`) should switch behaviour based on the placeholder type declared on
     * the string; this simple form is sufficient for the common `NSLocalizedString` case.
     */
    fun toIos(source: String): String {
        val placeholders = findTopLevelPlaceholders(source)
        if (placeholders.isEmpty()) return source
        val sb = StringBuilder()
        var lastEnd = 0
        for (ph in placeholders) {
            sb.append(source, lastEnd, ph.range.first)
            val specifier = when (ph.type.lowercase()) {
                "number", "integer", "int" -> "%d"
                "double", "float" -> "%f"
                else -> "%@"
            }
            sb.append(specifier)
            lastEnd = ph.range.last + 1
        }
        sb.append(source, lastEnd, source.length)
        return sb.toString()
    }

    /**
     * Pass-through: Nuxt i18n supports ICU `{name}` placeholders natively, so no
     * conversion is required. Provided so call sites can go through a single
     * dispatching helper.
     */
    fun toNuxt(source: String): String = source

    /**
     * Pass-through: ARB (Flutter Application Resource Bundle) uses ICU MessageFormat
     * natively including plural and select constructs.
     */
    fun toArb(source: String): String = source
}

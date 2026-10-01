package bosca.localization.placeholder

import bosca.localization.model.LocalizationPlaceholder

/**
 * Parses ICU MessageFormat placeholders out of a source string.
 *
 * Handles the three common ICU shapes the localization subsystem supports:
 * - `{name}` - simple variable substitution (typed as `string`)
 * - `{name, type}` - typed placeholder (`{count, number}`, `{when, date}`, etc.)
 * - `{name, type, style}` - typed with a format style (`{when, date, short}`)
 *
 * Nested plural/select constructs are intentionally not decomposed; for those,
 * only the outer argument name is extracted since that is what translators see
 * and must preserve.
 */
object PlaceholderExtractor {

    private val namePattern = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

    private fun findTopLevel(source: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
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
                    val name = parts[0].takeIf { namePattern.matches(it) } ?: continue
                    val type = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "string"
                    results.add(name to type)
                }
            } else {
                i++
            }
        }
        return results
    }

    /**
     * Extracts every declared placeholder from [source], deduplicating by [LocalizationPlaceholder.name].
     * When the same name appears twice with different types the first occurrence wins.
     */
    fun extract(source: String): List<LocalizationPlaceholder> {
        val seen = linkedMapOf<String, LocalizationPlaceholder>()
        for ((name, type) in findTopLevel(source)) {
            seen.putIfAbsent(name, LocalizationPlaceholder(name = name, type = type))
        }
        return seen.values.toList()
    }

    /** Returns the set of placeholder names present in [source]. */
    fun names(source: String): Set<String> = findTopLevel(source).map { it.first }.toSet()
}

package bosca.workops.deploy

/**
 * Flux-style version automation for Helm values files: sets the scalar at each
 * configured dot-path (e.g. `image.tag`) to the deploying version so the file in git always reflects
 * what is deployed.
 *
 * The rewrite is **line-surgical**, not parse-and-dump: only the matched key's scalar changes; every
 * other line — comments, blank lines, quoting, ordering — survives byte-for-byte. The file is owned
 * by humans; a deploy must not reformat it.
 *
 * Paths address nested mappings only (`a.b.c`). A path that doesn't exist in the file fails loudly —
 * a deploy config that promises to pin `image.tag` must never deploy without doing so.
 */
object ValuesVersionRewriter {

    private val mappingKey = Regex("""^(\s*)("[^"]+"|'[^']+'|[^\s:#][^:]*):(.*)$""")

    /** [content] with the scalar at every path in [paths] replaced by [version]. */
    fun rewrite(content: String, paths: List<String>, version: String): String {
        var lines = content.lines()
        for (path in paths) {
            lines = rewritePath(lines, path, version)
                ?: error("values file has no '$path' to set — fix the deploy config's versionPaths or add the key")
        }
        return lines.joinToString("\n")
    }

    /** [lines] with the scalar at [path] set to [version], or null when the path doesn't exist. */
    private fun rewritePath(lines: List<String>, path: String, version: String): List<String>? {
        val segments = path.split('.')
        val out = lines.toMutableList()
        // The chain of (indent, key) mapping entries leading to the current line.
        val stack = ArrayDeque<Pair<Int, String>>()
        for ((i, raw) in lines.withIndex()) {
            val trimmed = raw.trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) continue
            val match = mappingKey.matchEntire(raw) ?: continue
            val indent = match.groupValues[1].length
            val key = match.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
            stack.addLast(indent to key)
            if (stack.map { it.second } == segments) {
                val comment = trailingComment(match.groupValues[3])
                out[i] = buildString {
                    append(match.groupValues[1]).append(match.groupValues[2]).append(": ")
                    append('"').append(version).append('"')
                    if (comment.isNotEmpty()) append("  ").append(comment)
                }
                return out
            }
        }
        return null
    }

    /** The ` # …` trailing comment of a scalar line's remainder, or empty. */
    private fun trailingComment(rest: String): String {
        var inSingle = false
        var inDouble = false
        for (i in rest.indices) {
            when (rest[i]) {
                '\'' -> if (!inDouble) inSingle = !inSingle
                '"' -> if (!inSingle) inDouble = !inDouble
                '#' -> if (!inSingle && !inDouble && (i == 0 || rest[i - 1].isWhitespace())) {
                    return rest.substring(i).trim()
                }
            }
        }
        return ""
    }
}

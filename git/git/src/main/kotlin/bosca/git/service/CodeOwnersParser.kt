package bosca.git.service

/**
 * Parses a CODEOWNERS file following the GitHub format: each line is a
 * file-path glob pattern followed by one or more owner references
 * (`@username`, `@org/team`, or email). Lines starting with `#` are comments.
 * Patterns are evaluated bottom-up — the last matching pattern wins.
 */
object CodeOwnersParser {

    data class CodeOwnerEntry(
        val pattern: String,
        val owners: List<String>
    )

    /**
     * Parses the raw CODEOWNERS file content into a list of entries.
     * The list is in file order — callers should evaluate bottom-up for
     * last-match-wins semantics.
     */
    fun parse(content: String): List<CodeOwnerEntry> {
        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size < 2) return@mapNotNull null
                CodeOwnerEntry(
                    pattern = parts[0],
                    owners = parts.drop(1)
                )
            }
            .toList()
    }

    /**
     * Finds the owners for a given file path by evaluating CODEOWNERS entries
     * bottom-up. Returns an empty list if no pattern matches.
     */
    fun findOwners(entries: List<CodeOwnerEntry>, filePath: String): List<String> {
        for (entry in entries.asReversed()) {
            if (BranchProtectionService.matchesGlob(entry.pattern, filePath)) {
                return entry.owners
            }
        }
        return emptyList()
    }
}

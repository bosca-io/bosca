package bosca.git.model

/**
 * Extracts Work Ops task keys from text (commit messages, PR titles, branch names)
 * using the standard pattern `[A-Z][A-Z0-9_]+-\d+` (e.g. `PROJ-123`, `BUG_FIX-42`).
 *
 * This is the same regex defined in the Work Ops R26 spec for task-key linking.
 * When Work Ops modules are implemented, the PostReceiveHook and PullRequestService
 * will use this to create `TaskCommitReference` and `TaskPullRequestReference` rows.
 */
object TaskKeyExtractor {

    private val TASK_KEY_PATTERN = Regex("(?<key>[A-Z][A-Z0-9_]+-\\d+)")

    /**
     * Extracts all unique task keys from the given text.
     */
    fun extract(text: String): Set<String> {
        return TASK_KEY_PATTERN.findAll(text).map { it.groupValues[1] }.toSet()
    }

    /**
     * Extracts task keys from multiple sources (commit messages, PR title,
     * description, branch name) and returns the deduplicated union.
     */
    fun extractFromAll(vararg sources: String?): Set<String> {
        return sources.filterNotNull().flatMap { extract(it) }.toSet()
    }
}

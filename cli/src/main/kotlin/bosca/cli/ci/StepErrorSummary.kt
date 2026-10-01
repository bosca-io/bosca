package bosca.cli.ci

/**
 * A log line retained in [LogBuffer]'s bounded tail. Kept separate from
 * the upload buffer, which is cleared after every successful flush.
 */
data class TailLine(val content: String, val stream: String)

/**
 * Derives a short failure summary from the tail of a failed step's log
 * output. The summary is persisted on the step record (via
 * `updateStepStatus`) so the pipeline UI can show why a step failed
 * without the user paging through the full log file.
 *
 * Prefers stderr — compilers and build tools (gradle included) emit
 * their failure reports there — and falls back to the combined tail
 * when a tool wrote everything to stdout.
 */
object StepErrorSummary {

    const val MAX_LINES = 50
    const val MAX_CHARS = 4000

    fun build(tail: List<TailLine>): String? {
        val stderr = tail.filter { it.stream == "stderr" && it.content.isNotBlank() }
        val source = stderr.ifEmpty { tail.filter { it.content.isNotBlank() } }
        if (source.isEmpty()) return null
        val joined = source.takeLast(MAX_LINES).joinToString("\n") { it.content }
        // On overflow keep the end: build tools print the decisive line last
        // (gradle's "* What went wrong:" block, a final exception, etc.).
        return if (joined.length > MAX_CHARS) joined.takeLast(MAX_CHARS) else joined
    }
}

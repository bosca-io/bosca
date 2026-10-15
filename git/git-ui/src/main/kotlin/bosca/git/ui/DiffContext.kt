package bosca.git.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.git.ui.graphql.GitUiBlob

/** Unchanged lines revealed by one step of an expander. */
const val CONTEXT_EXPANSION_STEP = 20

/** Unchanged lines shown around a discussion that falls outside the diff's hunks. */
private const val DISCUSSION_CONTEXT_LINES = 3

/**
 * Reveals hidden lines of gap [index]: `up` extends the hunk below, `down` the hunk above, each by
 * [CONTEXT_EXPANSION_STEP] lines; `all` reveals the whole gap. [source] holds the new file's lines.
 */
fun DiffView.expandGap(index: Int, direction: String, source: List<String>): DiffView {
    val gap = gap(index) ?: error("These lines are already shown.")
    when (direction) {
        "up" -> require(gap.canExpandUp) { "Nothing to show above this change." }
        "down" -> require(gap.canExpandDown) { "Nothing to show below this change." }
        "all" -> Unit
        else -> throw IllegalArgumentException("Unknown expansion direction: $direction")
    }
    // Revealed lines join the hunk below unless the gap is the end of the file.
    val below = direction == "up" || (direction == "all" && gap.canExpandUp)
    val range = when (direction) {
        "up" -> maxOf(gap.newStart, gap.newEnd - CONTEXT_EXPANSION_STEP + 1)..gap.newEnd
        "down" -> gap.newStart..minOf(gap.newEnd, gap.newStart + CONTEXT_EXPANSION_STEP - 1)
        else -> gap.newStart..gap.newEnd
    }
    val lines = contextLines(range, gap.oldOffset, source)
    return copy(
        hunks = hunks.mapIndexed { i, hunk ->
            when {
                below && i == index -> hunk.copy(before = lines + hunk.before)
                !below && i == index - 1 -> hunk.copy(after = hunk.after + lines)
                else -> hunk
            }
        }
    )
}

/** Reveals every hidden line that can currently be placed. */
fun DiffView.expandAll(source: List<String>): DiffView =
    (0..hunks.size).fold(this) { view, index -> if (view.gap(index) != null) view.expandGap(index, "all", source) else view }

/** Hides lines revealed by expanders; lines revealed for discussions stay beside them. */
fun DiffView.collapseRevealed(): DiffView =
    copy(hunks = hunks.map { it.copy(before = emptyList(), after = emptyList()) })

/** Whether new-file [line] is unchanged and hidden between hunks. */
fun DiffView.hides(line: Int): Boolean =
    (0..hunks.size).any { index -> gap(index)?.let { line in it.newStart..it.newEnd } == true }

/**
 * Reveals [lines] (new-file numbering) with a few lines around each, as context-only hunks between
 * the existing ones. Lines that are already shown or outside the file are ignored.
 */
fun DiffView.reveal(lines: Collection<Int>, source: List<String>): DiffView {
    val windows = lines.distinct().sorted().mapNotNull { line ->
        val gap = (0..hunks.size).firstNotNullOfOrNull { index -> gap(index)?.takeIf { line in it.newStart..it.newEnd } }
            ?: return@mapNotNull null
        Triple(
            maxOf(gap.newStart, line - DISCUSSION_CONTEXT_LINES),
            minOf(gap.newEnd, line + DISCUSSION_CONTEXT_LINES),
            gap.oldOffset,
        )
    }
    if (windows.isEmpty()) return this
    val merged = windows.fold(mutableListOf<Triple<Int, Int, Int>>()) { acc, window ->
        val last = acc.lastOrNull()
        if (last != null && last.third == window.third && window.first <= last.second + 1) {
            acc[acc.lastIndex] = Triple(last.first, maxOf(last.second, window.second), last.third)
        } else acc += window
        acc
    }
    val revealed = merged.map { (start, end, offset) ->
        val context = contextLines(start..end, offset, source)
        DiffHunkView(start + offset, context.size, start, context.size, context.size, context, synthetic = true)
    }
    return copy(hunks = (hunks + revealed).sortedBy { it.firstNew })
}

/**
 * Applies [change] to [file] in these views with the new file's lines from [load]. Failures keep the
 * views unchanged and propagate so the caller can report them beside the file.
 */
internal suspend fun List<DiffView>.withRevealedLines(
    file: String,
    load: suspend (DiffView) -> List<String>,
    change: (DiffView, List<String>) -> DiffView,
): List<DiffView> {
    val view = firstOrNull { it.path == file } ?: error("This file is no longer in the diff.")
    val next = change(view, load(view))
    return map { if (it.path == file) next else it }
}

/** Splits file content into lines the way the diff numbers them. */
fun fileLines(content: String): List<String> =
    if (content.isEmpty()) emptyList() else content.removeSuffix("\n").split('\n')

/**
 * Loads the new version of [file] at [ref] for revealing unchanged lines, failing when it cannot be
 * shown or no longer matches the diff.
 */
internal suspend fun GraphQLClient.diffSourceLines(repositoryId: String, ref: String, file: DiffView): List<String> {
    val path = file.newPath ?: error("Deleted files have no unchanged lines to show.")
    val blob = execute(GitUiBlob, GitUiBlob.Variables(repositoryId, ref, path)).git.blob
        ?: error("$path is no longer available at this revision. Reload to see the current changes.")
    val content = blob.content ?: error("$path is too large or binary, so unchanged lines cannot be shown.")
    return fileLines(content).also {
        require(it.size == file.newLineCount) { "$path changed. Reload to see the current changes." }
    }
}

/** Loads the new version of [file] at [ref], or null when it is unavailable, not text, or no longer matches the diff. */
internal suspend fun GraphQLClient.diffSourceLinesOrNull(repositoryId: String, ref: String, file: DiffView): List<String>? {
    val path = file.newPath ?: return null
    val content = execute(GitUiBlob, GitUiBlob.Variables(repositoryId, ref, path)).git.blob?.content ?: return null
    return fileLines(content).takeIf { it.size == file.newLineCount }
}

private fun contextLines(range: IntRange, oldOffset: Int, source: List<String>): List<DiffLineView> =
    range.map { line ->
        DiffLineView("CONTEXT", line + oldOffset, line, source.getOrNull(line - 1) ?: error("The file changed. Reload to see the current changes."))
    }

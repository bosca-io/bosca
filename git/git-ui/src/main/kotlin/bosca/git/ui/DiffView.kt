package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class DiffView(
    val oldPath: String?,
    val newPath: String?,
    val changeType: String,
    val hunks: List<DiffHunkView>,
    val newLineCount: Int = 0,
) {
    val path: String
        get() = newPath ?: oldPath.orEmpty()

    val displayPath: String
        get() =
            if (!oldPath.isNullOrBlank() && !newPath.isNullOrBlank() && oldPath != newPath)
                "$oldPath → $newPath"
            else path

    val anchor: String
        get() = "diff-$path"

    val truncated: Boolean
        get() = hunks.any { it.totalLineCount > it.lines.size }

    val emptyTextChange: Boolean
        get() = hunks.isNotEmpty() && hunks.all {
            it.oldCount == 0 && it.newCount == 0 && it.totalLineCount == 0
        }

    val emptyTextLabel: String
        get() = when (changeType) {
            "ADD" -> "Empty file added."
            "DELETE" -> "Empty file deleted."
            else -> "No text changes."
        }

    val additions: Int
        get() = hunks.sumOf { h -> h.lines.count { it.type == "ADD" } }

    val deletions: Int
        get() = hunks.sumOf { h -> h.lines.count { it.type == "DELETE" } }

    /** Hunks in order, each with the hidden lines above it. */
    val sections: List<DiffSection>
        get() = hunks.mapIndexed { index, hunk -> DiffSection(index, gap(index), hunk) }

    /** Hidden lines after the last hunk. */
    val bottomGap: DiffGap?
        get() = gap(hunks.size)

    val hasHiddenLines: Boolean
        get() = (0..hunks.size).any { gap(it) != null }

    val hasRevealedLines: Boolean
        get() = hunks.any { it.revealedCount > 0 }

    /**
     * Hidden lines above hunk [index], or after the last hunk when [index] equals the hunk count.
     * Returns null when nothing is hidden there or when the file has no new-side text to reveal.
     */
    fun gap(index: Int): DiffGap? {
        if (newLineCount <= 0 || hunks.isEmpty() || hunks.any { it.newCount == 0 }) return null
        val above = hunks.getOrNull(index - 1)
        val below = hunks.getOrNull(index)
        val start = above?.let { it.lastNew + 1 } ?: 1
        val end = below?.let { it.firstNew - 1 } ?: newLineCount
        if (end < start) return null
        val canExpandDown = above?.complete == true
        val offset = when {
            below != null -> below.firstOld - below.firstNew
            above != null && canExpandDown -> above.lastOld - above.lastNew
            else -> return null
        }
        return DiffGap(index, start, end, offset, below != null, canExpandDown, below?.heading.orEmpty())
    }
}

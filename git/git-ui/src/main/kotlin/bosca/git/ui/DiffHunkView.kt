package bosca.git.ui

import kotlinx.serialization.Serializable

/**
 * One diff hunk. [before] and [after] hold unchanged lines revealed above and below it; a
 * [synthetic] hunk holds only revealed lines and has no counterpart in the API's hunk list.
 */
@Serializable
data class DiffHunkView(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val totalLineCount: Int,
    val lines: List<DiffLineView>,
    val before: List<DiffLineView> = emptyList(),
    val after: List<DiffLineView> = emptyList(),
    val synthetic: Boolean = false,
) {
    val heading: String
        get() = "@@ -$firstOld,${oldCount + revealedCount} +$firstNew,${newCount + revealedCount} @@"

    /** Lines in display order; revealed lines below a partially loaded hunk wait for the rest of it. */
    val displayLines: List<DiffLineView>
        get() = before + lines + after

    val complete: Boolean
        get() = lines.size >= totalLineCount

    val revealedCount: Int
        get() = before.size + after.size

    internal val firstNew: Int
        get() = newStart - before.size

    internal val firstOld: Int
        get() = oldStart - before.size

    internal val lastNew: Int
        get() = newStart + newCount - 1 + after.size

    internal val lastOld: Int
        get() = oldStart + oldCount - 1 + after.size
}

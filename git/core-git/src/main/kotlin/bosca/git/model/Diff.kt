package bosca.git.model

import kotlinx.serialization.Serializable

/**
 * Structured representation of a file change within a diff, carrying
 * the old and new paths, change classification, and line-level hunks.
 * [newLineCount] is the number of text lines in the new version, zero when it
 * was deleted or is binary, so clients can reveal unchanged lines between hunks.
 */
@Serializable
data class DiffFile(
    val oldPath: String?,
    val newPath: String?,
    val changeType: DiffChangeType,
    val hunks: List<DiffHunk>,
    val newLineCount: Int = 0,
)

@Serializable
enum class DiffChangeType {
    ADD, MODIFY, DELETE, RENAME, COPY
}

/**
 * A contiguous region of changed lines within a file diff, including up to
 * three unchanged lines of context on each side.
 */
@Serializable
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>
)

/**
 * A single line within a diff hunk, classified as context, addition, or deletion.
 */
@Serializable
data class DiffLine(
    val type: DiffLineType,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val content: String
)

@Serializable
enum class DiffLineType {
    CONTEXT, ADD, DELETE
}

@Serializable
enum class DiffSide {
    LEFT, RIGHT
}

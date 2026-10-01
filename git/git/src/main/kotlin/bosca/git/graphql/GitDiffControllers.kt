package bosca.git.graphql

import bosca.git.model.DiffChangeType
import bosca.git.model.DiffFile
import bosca.git.model.DiffHunk
import bosca.git.model.DiffLine
import bosca.git.model.DiffLineType
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Resolves all fields on [GitDiffFile] representing a single file changed
 * between two revisions.
 */
@TypeController(type = "GitDiffFile")
class GitDiffFileController : GraphQLController<DiffFile> {

    @Field
    fun oldPath(source: DiffFile): String? = source.oldPath

    @Field
    fun newPath(source: DiffFile): String? = source.newPath

    @Field
    fun changeType(source: DiffFile): DiffChangeType = source.changeType

    @Field
    fun hunks(source: DiffFile): List<DiffHunk> = source.hunks
}

/**
 * Resolves all fields on [GitDiffHunk] representing a contiguous region of
 * changes within a diff file.
 */
@TypeController(type = "GitDiffHunk")
class GitDiffHunkController : GraphQLController<DiffHunk> {

    @Field
    fun oldStart(source: DiffHunk): Int = source.oldStart

    @Field
    fun oldCount(source: DiffHunk): Int = source.oldCount

    @Field
    fun newStart(source: DiffHunk): Int = source.newStart

    @Field
    fun newCount(source: DiffHunk): Int = source.newCount

    @Field
    fun totalLineCount(source: DiffHunk): Int = source.lines.size

    @Field
    fun lines(source: DiffHunk, offset: Int?, limit: Int?): List<DiffLine> {
        val start = (offset ?: 0).coerceIn(0, source.lines.size)
        val pageSize = (limit ?: DEFAULT_DIFF_LINE_LIMIT).coerceIn(1, MAX_DIFF_LINE_LIMIT)
        val end = minOf(source.lines.size.toLong(), start.toLong() + pageSize).toInt()
        return source.lines.subList(start, end)
    }

    private companion object {
        const val DEFAULT_DIFF_LINE_LIMIT = 2_000
        const val MAX_DIFF_LINE_LIMIT = 20_000
    }
}

/**
 * Resolves all fields on [GitDiffLine] representing a single line within a
 * diff hunk, with its type (context, add, delete) and position.
 */
@TypeController(type = "GitDiffLine")
class GitDiffLineController : GraphQLController<DiffLine> {

    @Field
    fun type(source: DiffLine): DiffLineType = source.type

    @Field
    fun oldLineNumber(source: DiffLine): Int? = source.oldLineNumber

    @Field
    fun newLineNumber(source: DiffLine): Int? = source.newLineNumber

    @Field
    fun content(source: DiffLine): String = source.content
}

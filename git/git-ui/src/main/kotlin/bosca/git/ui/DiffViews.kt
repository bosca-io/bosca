package bosca.git.ui

import bosca.git.ui.graphql.*

internal fun GitUiCompareData.Git.Compare.Files.view(): DiffView =
    DiffView(
        oldPath,
        newPath,
        changeType.name,
        hunks.map { h ->
            DiffHunkView(
                h.oldStart,
                h.oldCount,
                h.newStart,
                h.newCount,
                h.totalLineCount,
                h.lines.map {
                    DiffLineView(it.type.name, it.oldLineNumber, it.newLineNumber, it.content)
                },
            )
        },
        newLineCount,
    )

internal fun GitUiPullDiffPageData.Git.PullRequest.Diff.view(): DiffView =
    DiffView(
        oldPath,
        newPath,
        changeType.name,
        hunks.map { h ->
            DiffHunkView(
                h.oldStart,
                h.oldCount,
                h.newStart,
                h.newCount,
                h.totalLineCount,
                h.lines.map {
                    DiffLineView(it.type.name, it.oldLineNumber, it.newLineNumber, it.content)
                },
            )
        },
        newLineCount,
    )

internal fun diffView(file: GitUiCompareData.Git.Compare.Files): DiffView = file.view()

/** Builds a folder-first navigation tree while retaining the full path of each changed file. */
fun changedFileTree(files: List<DiffView>): List<DiffTreeEntry> {
    val filePaths = files.map { it.path }.toSet()
    val folders =
        filePaths
            .flatMap { path ->
                val parts = path.split('/')
                (1 until parts.size).map { parts.take(it).joinToString("/") }
            }
            .toSet()
    val paths = folders + filePaths
    fun children(parent: String, depth: Int): List<DiffTreeEntry> =
        paths
            .filter { it.substringBeforeLast('/', "") == parent }
            .sortedWith(compareBy({ it !in folders }, { it.substringAfterLast('/').lowercase() }))
            .flatMap { path ->
                listOf(DiffTreeEntry(path, path.substringAfterLast('/'), path in folders, depth)) +
                    if (path in folders) children(path, depth + 1) else emptyList()
            }
    return children("", 0)
}

package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class DiffView(
    val oldPath: String?,
    val newPath: String?,
    val changeType: String,
    val hunks: List<DiffHunkView>,
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
}

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
}

package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class SearchHit(
    val path: String,
    val line: Int,
    val snippet: String,
    val href: String,
    val kind: String = "BLOB",
    val size: Long? = null,
) {
    val name: String
        get() = path.substringAfterLast('/')

    val icon: String
        get() =
            when (kind) {
                "TREE" -> "folder"
                "SUBMODULE",
                "SYMLINK" -> "link"
                else -> "file"
            }

    val sizeLabel: String
        get() = size?.takeIf { kind != "TREE" }?.let(::formatSize).orEmpty()

    val typeLabel: String
        get() =
            when (kind) {
                "SUBMODULE" -> "Submodule"
                "SYMLINK" -> "Symbolic link"
                else -> ""
            }

    fun parts(query: String): List<SearchSnippetPart> = searchSnippetParts(snippet, query)
}

package bosca.git.ui

import kotlinx.serialization.Serializable

/** Commit-pinned inputs for the independently loaded repository tree. */
@Serializable
data class RepositorySidebarContext(
    val id: String,
    val ref: String,
    val resolvedSha: String,
    val file: String,
    val path: String,
    val listHref: String,
) {
    fun href(path: String, file: String): String = repositoryHref(id, ref, path, file, listHref)
}

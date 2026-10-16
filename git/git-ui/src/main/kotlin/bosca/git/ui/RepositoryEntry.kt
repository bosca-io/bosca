package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class RepositoryEntry(
    val name: String,
    val directory: Boolean,
    val href: String,
    val kind: String,
    /** Blob size in bytes; directories have none. */
    val size: Long? = null,
    /** Repository path, used for per-entry edit links. */
    val path: String = "",
    /** Edit link for files, blank for directories. */
    val editHref: String = "",
)

package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class RepositorySummary(
    val id: String,
    val name: String,
    val description: String,
    val defaultBranch: String,
    val visibility: String,
    val archived: Boolean,
    val slug: String,
    val contentType: String,
    val size: Long,
    val updated: String,
    val listHref: String,
    val canManage: Boolean,
    val canExecute: Boolean,
) {
    val href: String
        get() = "/repositories/${encode(id)}?return=${encode(listHref)}"
}

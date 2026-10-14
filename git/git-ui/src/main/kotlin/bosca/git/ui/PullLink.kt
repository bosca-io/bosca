package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class PullLink(
    val id: String,
    val repository: String,
    val number: Int,
    val title: String,
    val status: String,
    val source: String = "",
    val target: String = "",
    val updated: String = "",
) {
    val href: String
        get() = pullHref(id, repository, number)
}

package bosca.git.ui

import bosca.git.ui.components.NavigationItem
import kotlinx.serialization.Serializable

@Serializable
data class PullListPage(
    val repositories: List<RepositorySummary>,
    val repository: String,
    val status: String,
    val offset: Long,
    val rows: List<PullLink>,
    val source: String,
    val target: String,
    val sourceRepository: String,
    val branches: List<String>,
    val showCreate: Boolean,
    val canEdit: Boolean,
    val targetBranches: List<String> = emptyList(),
    val baseHref: String = "",
) {
    val tabs: List<NavigationItem>
        get() =
            listOf("Open", "Draft", "Merged", "Closed", "All").map {
                NavigationItem(it, href(status = it.uppercase()), it.uppercase() == status)
            }

    fun href(status: String = this.status, offset: Long = 0): String =
        if (baseHref.isNotBlank()) "$baseHref&status=$status&offset=$offset"
        else "/pulls?status=$status&repo=${encode(repository)}&offset=$offset"
}

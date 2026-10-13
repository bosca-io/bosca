package bosca.git.ui

import bosca.git.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
data class ExplorePage(
    val section: String,
    val list: RepositoryListModel,
    val id: String,
    val ref: String,
    val base: String,
    val head: String,
    val sha: String,
    val branches: List<String>,
    val comparison: GitUiCompareData.Git.Compare?,
    val statuses: List<GitUiStatusesData.Git.CommitStatuses>,
    val recent: List<CommitSummary>,
    val baseHref: String = "",
) {
    fun statusHref(commit: String): String =
        if (baseHref.isNotBlank()) "$baseHref&sha=${encode(commit)}"
        else "/statuses?repo=${encode(id)}&owner=${encode(list.owner.id)}&sha=${encode(commit)}"

    fun browseHref(commit: String): String = repositoryHref(id, commit)

    val createPullHref: String
        get() = "/pulls?repo=${encode(id)}&base=${encode(base)}&head=${encode(head)}&new=true"
}

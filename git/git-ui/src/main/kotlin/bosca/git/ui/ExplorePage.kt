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

    /** Path that the commit SHA lookup form submits to. */
    val shaFormAction: String
        get() = statusHref("").substringBefore('?')

    /** Query parameters the commit SHA lookup form keeps, excluding the SHA itself. */
    val shaFormFields: List<Pair<String, String>>
        get() =
            statusHref("")
                .substringAfter('?', "")
                .split('&')
                .filter { it.isNotBlank() }
                .map { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
                .filter { it.first != "sha" }

    /** The same comparison with base and head exchanged. */
    val swapHref: String
        get() =
            "/compare?owner=${encode(list.owner.id)}&repo=${encode(id)}&base=${encode(head)}&head=${encode(base)}"

    fun browseHref(commit: String): String = repositoryHref(id, commit)

    val createPullHref: String
        get() = "/pulls?repo=${encode(id)}&base=${encode(base)}&head=${encode(head)}&new=true"
}

package bosca.git.ui

import bosca.git.ui.components.NavigationItem
import bosca.git.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
data class PullPage(
    val pull: GitUiPullData.Git.PullRequest,
    val href: String,
    val cloneUrl: String,
    val headSha: String,
    val currentProfileIds: List<String>,
    val descriptionHtml: String,
    val checks: List<GitUiStatusesData.Git.CommitStatuses>,
    val candidates: List<PullLink>,
    val identities: Map<String, String>,
    val canEdit: Boolean,
    val candidateCount: Int,
    val view: String,
    val reviewing: Boolean,
) {
    val tabs: List<NavigationItem>
        get() =
            listOf("overview" to "Overview", "files" to "Files Changed", "reviews" to "Reviews")
                .map {
                    NavigationItem(it.second, "$href&view=${it.first}", view == it.first)
                }

    val sourceRepository: String
        get() = pull.sourceRepositoryId ?: pull.repositoryId

    fun identity(id: String): String = identities[id] ?: id

    fun fileHref(path: String, line: Int?): String =
        repositoryHref(sourceRepository, headSha, file = path) + (line?.let { "&line=$it" } ?: "")
}

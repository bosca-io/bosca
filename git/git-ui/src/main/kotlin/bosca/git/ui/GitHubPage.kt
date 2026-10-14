package bosca.git.ui

import bosca.git.ui.components.NavigationItem
import bosca.git.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
data class GitHubPage(
    val id: String,
    val name: String,
    val allowed: Boolean,
    val pair: GitUiGitHubPairData.Github.Pair?,
    val secrets: List<String>,
    val view: String,
    val offset: Long,
    val refs: List<GitUiGitHubRefsData.Github.RefStates>,
    val pulls: List<GitUiGitHubPullStatesData.Github.PullRequestStates>,
    val deliveries: List<GitUiGitHubDeliveriesData.Github.Deliveries>,
) {
    val tabs: List<NavigationItem>
        get() =
            listOf(
                    "refs" to "Branches and tags",
                    "pulls" to "Pull requests",
                    "deliveries" to "Deliveries",
                )
                .map { NavigationItem(it.second, githubHref(id, it.first), it.first == view) }

    val hasNext: Boolean
        get() = refs.size > 25 || pulls.size > 25 || deliveries.size > 25

    val href: String
        get() = githubHref(id, view, offset)
}

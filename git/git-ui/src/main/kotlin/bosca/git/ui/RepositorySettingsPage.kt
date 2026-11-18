package bosca.git.ui

import bosca.design.model.NavigationItem
import bosca.git.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
data class RepositorySettingsPage(
    val repository: GitUiRepositoryData.Git.RepositoryById,
    val panel: String,
    val webhooks: List<GitUiWebhooksData.Git.Webhooks>,
    val rules: List<GitUiProtectionData.Git.BranchProtectionRules>,
    val permissions: List<GitUiPermissionsData.Git.RepositoryById.Permissions>,
    val groups: List<GitUiGroupsData.Security.Groups.All>,
    val baseHref: String = "",
) {
    val href: String
        get() = panelHref(panel)

    fun panelHref(panel: String): String =
        if (baseHref.isBlank()) settingsHref(repository.id, panel) else "$baseHref&panel=$panel"

    val tabs: List<NavigationItem>
        get() =
            listOf("metadata", "webhooks", "protection", "permissions", "utilities").map {
                NavigationItem(
                    if (it == "protection") "Branch Protection" else it.replaceFirstChar(Char::uppercase),
                    panelHref(it),
                    panel == it,
                )
            }
}

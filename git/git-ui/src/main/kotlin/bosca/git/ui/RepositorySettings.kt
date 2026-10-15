package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext
import bosca.git.ui.graphql.*

internal fun settingsHref(id: String, panel: String): String =
    "/repositories/${encode(id)}/settings?panel=$panel"

internal fun commaValues(value: String): List<String> =
    value.split(',').map(String::trim).filter(String::isNotEmpty).distinct()

/** Loads only the selected settings panel, keeping all actions tied to one repository. */
suspend fun repositorySettingsPage(
    baseHref: String = "",
    repository: GitUiRepositoryData.Git.RepositoryById? = null,
): RepositorySettingsPage? {
    val ctx = currentRenderContext()
    val id = ctx.params["id"].orEmpty()
    val repo = repository ?:
        ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(id)).git.repositoryById
            ?: return null.also { ctx.notFound = true }
    val panel =
        ctx.query["panel"]?.takeIf {
            it in setOf("metadata", "webhooks", "protection", "permissions", "utilities")
        } ?: "metadata"
    val webhooks =
        if (panel == "webhooks" && repo.canManage)
            ctx.gql.execute(GitUiWebhooks, GitUiWebhooks.Variables(id)).git.webhooks
        else emptyList()
    val rules =
        if (panel == "protection" && repo.canManage)
            ctx.gql
                .execute(GitUiProtection, GitUiProtection.Variables(id))
                .git
                .branchProtectionRules
        else emptyList()
    val permissions =
        if (panel == "permissions" && repo.canManage)
            ctx.gql
                .execute(GitUiPermissions, GitUiPermissions.Variables(id))
                .git
                .repositoryById
                ?.permissions
                .orEmpty()
        else emptyList()
    val groups = mutableListOf<GitUiGroupsData.Security.Groups.All>()
    if (panel == "permissions" && repo.canManage) {
        var offset = 0L
        do {
            val batch =
                ctx.gql.execute(GitUiGroups, GitUiGroups.Variables(offset)).security.groups.all
            groups.addAll(batch)
            offset += batch.size
        } while (batch.size == 100)
    }
    return RepositorySettingsPage(repo, panel, webhooks, rules, permissions, groups, baseHref)
}

suspend fun repositorySecretsPage(
    baseHref: String = "",
    repository: GitUiRepositoryData.Git.RepositoryById? = null,
): RepositorySecretsPage? {
    val ctx = currentRenderContext()
    val id = ctx.params["id"].orEmpty()
    val repo = repository ?:
        ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(id)).git.repositoryById
            ?: return null.also { ctx.notFound = true }
    return RepositorySecretsPage(
        id,
        repo.name,
        if (repo.canManage)
            ctx.gql.execute(GitUiSecrets, GitUiSecrets.Variables(id)).git.pipelineSecrets
        else emptyList(),
        repo.canManage,
        baseHref,
    )
}

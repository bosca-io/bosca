package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext
import bosca.git.ui.graphql.*

/** Loads read-only Git exploration using the caller's selected owner and authorized API. */
suspend fun explorePage(
    section: String,
    repositoryId: String = "",
    baseHref: String = "",
): ExplorePage {
    val ctx = currentRenderContext()
    val list =
        if (repositoryId.isBlank()) repositoryList()
        else {
            val repo =
                ctx.gql
                    .execute(GitUiRepository, GitUiRepository.Variables(repositoryId))
                    .git
                    .repositoryById ?: error("Repository unavailable.")
            RepositoryListModel(
                RepositoryState.ACTIVE,
                OwnerOption("all", "All accessible repositories", ""),
                repositories = listOf(repositorySummary(repo)),
            )
        }
    val selected =
        repositoryId.takeIf(String::isNotBlank)
            ?: ctx.query["repo"]
            ?: list.repositories.firstOrNull()?.id.orEmpty()
    val repository = list.repositories.firstOrNull { it.id == selected }
    val id = repository?.id.orEmpty()
    val ref = ctx.query["ref"]?.takeIf(String::isNotBlank) ?: repository?.defaultBranch ?: "main"
    val base = ctx.query["base"]?.takeIf(String::isNotBlank) ?: repository?.defaultBranch ?: "main"
    val head = ctx.query["head"].orEmpty()
    val sha = ctx.query["sha"].orEmpty()
    val branches =
        if (id.isNotBlank())
            ctx.gql.execute(GitUiBranches, GitUiBranches.Variables(id)).git.branches.map { it.name }
        else emptyList()
    val comparison =
        if (section == "compare" && id.isNotBlank() && head.isNotBlank())
            ctx.gql.execute(GitUiCompare, GitUiCompare.Variables(id, base, head)).git.compare
        else null
    val statuses =
        if (section == "statuses" && id.isNotBlank() && sha.isNotBlank())
            ctx.gql.execute(GitUiStatuses, GitUiStatuses.Variables(id, sha)).git.commitStatuses
        else emptyList()
    val recent =
        if (section == "statuses" && id.isNotBlank())
            ctx.gql
                .execute(GitUiCommits, GitUiCommits.Variables(id, ref, null, 0))
                .git
                .commits
                .map { CommitSummary(it.sha, it.message, it.authorName, it.authorDate) }
        else emptyList()
    return ExplorePage(
        section,
        list,
        id,
        ref,
        base,
        head,
        sha,
        branches,
        comparison,
        statuses,
        recent,
        baseHref,
    )
}

/** Only web destinations may be opened from user-authored status and webhook metadata. */
internal fun safeWebHref(value: String?): String =
    value
        ?.takeIf {
            runCatching { java.net.URI(it).scheme?.lowercase() in setOf("https", "http") }
                .getOrDefault(false)
        }
        .orEmpty()

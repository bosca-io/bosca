package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext
import bosca.git.ui.graphql.*
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

internal fun githubHref(id: String, view: String = "refs", offset: Long = 0): String =
    "/repositories/${encode(id)}/github?view=$view&offset=$offset"

/** Loads administrator-only pairing and a single paged synchronization history view. */
suspend fun gitHubPage(): GitHubPage? = supervisorScope {
    val ctx = currentRenderContext()
    val id = ctx.params["id"].orEmpty()
    val accessRequest = async { isGitAdministrator(ctx) }
    val repository =
        ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(id)).git.repositoryById
            ?: run {
                accessRequest.cancel()
                return@supervisorScope null.also { ctx.notFound = true }
            }
    val view = ctx.query["view"]?.takeIf { it in setOf("refs", "pulls", "deliveries") } ?: "refs"
    val offset = ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
    if (!accessRequest.await())
        return@supervisorScope GitHubPage(
            id,
            repository.name,
            false,
            null,
            emptyList(),
            view,
            offset,
            emptyList(),
            emptyList(),
            emptyList(),
        )
    val pairRequest = async { ctx.gql.execute(GitUiGitHubPair, GitUiGitHubPair.Variables(id)) }
    val refsRequest = async {
        if (view == "refs")
            ctx.gql.execute(GitUiGitHubRefs, GitUiGitHubRefs.Variables(id, offset)).github.refStates
        else emptyList()
    }
    val pullsRequest = async {
        if (view == "pulls")
            ctx.gql
                .execute(GitUiGitHubPullStates, GitUiGitHubPullStates.Variables(id, offset))
                .github
                .pullRequestStates
        else emptyList()
    }
    val deliveriesRequest = async {
        if (view == "deliveries")
            ctx.gql
                .execute(GitUiGitHubDeliveries, GitUiGitHubDeliveries.Variables(id, offset))
                .github
                .deliveries
        else emptyList()
    }
    val result = pairRequest.await()
    GitHubPage(
        id,
        repository.name,
        true,
        result.github.pair,
        result.pipelines.secrets.map { it.name },
        view,
        offset,
        refsRequest.await(),
        pullsRequest.await(),
        deliveriesRequest.await(),
    )
}

suspend fun gitHubUsersPage(): GitHubUsersModel {
    val ctx = currentRenderContext()
    val model = GitHubUsersModel(isGitAdministrator(ctx))
    if (model.allowed) model.reload(ctx)
    return model
}

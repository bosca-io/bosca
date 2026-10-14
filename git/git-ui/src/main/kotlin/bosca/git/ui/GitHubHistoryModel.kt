package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class GitHubHistoryModel(
    var page: GitHubPage,
    var reviewed: GitUiGitHubRefsData.Github.RefStates? = null,
    var message: String = "",
    var failed: Boolean = false,
) {
    suspend fun review(ref: String) {
        reviewed = page.refs.first { it.ref == ref && it.conflict }
        message = ""
    }

    suspend fun dismiss() {
        reviewed = null
    }

    suspend fun synchronize(action: String, confirmed: Boolean, ctx: RenderContext) =
        perform(ctx) {
            require(confirmed && page.pair?.enabled == true) {
                "Enable synchronization and confirm this action."
            }
            when (action) {
                "pull" -> ctx.gql.execute(GitUiPullRefs, GitUiPullRefs.Variables(page.id))
                "push" -> ctx.gql.execute(GitUiPushRefs, GitUiPushRefs.Variables(page.id))
                "refs" -> ctx.gql.execute(GitUiReconcileRefs, GitUiReconcileRefs.Variables(page.id))
                "pulls" ->
                    ctx.gql.execute(GitUiReconcilePulls, GitUiReconcilePulls.Variables(page.id))
                else -> error("Unknown synchronization action")
            }
            message =
                "Synchronization finished. Review the observed state and any remaining problems."
        }

    suspend fun resolve(choice: String, confirmed: Boolean, ctx: RenderContext) =
        perform(ctx) {
            val state = requireNotNull(reviewed) { "Review a recorded conflict first." }
            require(confirmed && page.pair?.enabled == true) {
                "Confirm the reviewed conflict resolution."
            }
            ctx.gql.execute(
                GitUiResolveRef,
                GitUiResolveRef.Variables(
                    GitHubRefResolutionInput(
                        page.id,
                        state.ref,
                        GitHubRefResolution.valueOf(choice),
                        state.boscaSha,
                        state.githubSha,
                    )
                ),
            )
            reviewed = null
            message = "Ref resolved. Review the observed state."
        }

    suspend fun refresh(ctx: RenderContext) {
        message = ""
        failed = false
        try {
            loadHistory(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "History refresh failed."
        }
    }

    private suspend fun loadHistory(ctx: RenderContext) {
        page =
            when (page.view) {
                "refs" ->
                    page.copy(
                        refs =
                            ctx.gql
                                .execute(
                                    GitUiGitHubRefs,
                                    GitUiGitHubRefs.Variables(page.id, page.offset),
                                )
                                .github
                                .refStates
                    )
                "pulls" ->
                    page.copy(
                        pulls =
                            ctx.gql
                                .execute(
                                    GitUiGitHubPullStates,
                                    GitUiGitHubPullStates.Variables(page.id, page.offset),
                                )
                                .github
                                .pullRequestStates
                    )
                else ->
                    page.copy(
                        deliveries =
                            ctx.gql
                                .execute(
                                    GitUiGitHubDeliveries,
                                    GitUiGitHubDeliveries.Variables(page.id, page.offset),
                                )
                                .github
                                .deliveries
                    )
            }
    }

    private suspend fun perform(ctx: RenderContext, block: suspend () -> Unit) {
        message = ""
        failed = false
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Synchronization failed."
        }
        try {
            loadHistory(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message += " History refresh failed: ${e.message}"
        }
    }
}

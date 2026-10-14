package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class PullActionsModel(
    val page: PullPage,
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
    var candidateOffset: Long = 0,
    var candidates: List<PullLink> = page.candidates,
    var candidateHasNext: Boolean = page.candidateCount == 50,
    var strategy: String = "MERGE_COMMIT",
    var selectedAction: String = "",
    var plan: List<GitUiMergePlanData.Git.PullRequestMergePlan> = emptyList(),
) {
    val mergeBlocked: Boolean
        get() = plan.any { it.status.name != "OPEN" || !it.mergeable }

    val dependencyCandidates: List<PullLink>
        get() = candidates.filter {
            it.status in setOf("OPEN", "DRAFT") &&
                page.pull.dependencies.none { dependency -> dependency.id == it.id }
        }

    suspend fun loadCandidates(offset: Long, ctx: RenderContext) = perform {
        candidateOffset = offset.coerceAtLeast(0)
        val batch =
            ctx.gql
                .execute(GitUiPullList, GitUiPullList.Variables(null, candidateOffset))
                .git
                .allPullRequests
        candidateHasNext = batch.size == 50
        candidates =
            batch
                .filter { it.id != page.pull.id }
                .map { PullLink(it.id, it.repositoryId, it.number, it.title, it.status.name) }
    }

    suspend fun lifecycle(action: String, confirmed: Boolean, ctx: RenderContext) = perform {
        selectedAction = action
        require(confirmed) { "Confirm this action." }
        when (action) {
            "close" -> ctx.gql.execute(GitUiClosePull, GitUiClosePull.Variables(page.pull.id))
            "reopen" -> ctx.gql.execute(GitUiReopenPull, GitUiReopenPull.Variables(page.pull.id))
            "ready" -> ctx.gql.execute(GitUiReadyPull, GitUiReadyPull.Variables(page.pull.id))
            else -> error("Unknown pull request action")
        }
        nextHref = page.href
    }

    suspend fun prepare(strategy: String, ctx: RenderContext) = perform {
        selectedAction = "merge"
        this.strategy = strategy
        GitMergeStrategy.valueOf(strategy)
        requireReviewedHead(ctx)
        plan =
            ctx.gql
                .execute(GitUiMergePlan, GitUiMergePlan.Variables(page.pull.id))
                .git
                .pullRequestMergePlan
        require(plan.isNotEmpty()) { "No merge plan is available." }
        require(plan.last().version == page.pull.version) { "Pull request changed. Reload it before merging." }
    }

    suspend fun merge(confirmed: Boolean, ctx: RenderContext) = perform {
        selectedAction = "merge"
        require(confirmed && plan.isNotEmpty()) { "Review and confirm the merge plan first." }
        requireReviewedHead(ctx)
        val current =
            ctx.gql
                .execute(GitUiMergePlan, GitUiMergePlan.Variables(page.pull.id))
                .git
                .pullRequestMergePlan
        if (current != plan) {
            plan = current
            error("The merge plan changed. Review it and confirm again.")
        }
        if (plan.size == 1)
            ctx.gql.execute(
                GitUiMergePull,
                GitUiMergePull.Variables(page.pull.id, GitMergeStrategy.valueOf(strategy), page.pull.version),
            )
        else
            ctx.gql.execute(
                GitUiMergePullDependencies,
                GitUiMergePullDependencies.Variables(
                    page.pull.id,
                    GitMergeStrategy.valueOf(strategy),
                    page.pull.version,
                ),
            )
        nextHref = page.href
    }

    fun resetMerge() {
        plan = emptyList()
        message = ""
        failed = false
    }

    private suspend fun requireReviewedHead(ctx: RenderContext) {
        val current = ctx.gql.commitShaForRef(page.sourceRepository, page.pull.sourceBranch)
        require(current == page.headSha && current.isNotBlank()) {
            "The source branch changed. Reload and review the pull request before merging."
        }
    }

    suspend fun assign(profile: String, remove: Boolean, ctx: RenderContext) = perform {
        selectedAction = if (remove) "unassign" else "assign"
        require(profile.isNotBlank() && profile != "all") { "Choose a profile." }
        if (remove)
            ctx.gql.execute(GitUiUnassignPull, GitUiUnassignPull.Variables(page.pull.id, profile))
        else ctx.gql.execute(GitUiAssignPull, GitUiAssignPull.Variables(page.pull.id, profile))
        nextHref = page.href
    }

    suspend fun dependency(id: String, remove: Boolean, ctx: RenderContext) = perform {
        selectedAction = if (remove) "remove-dependency" else "add-dependency"
        require(id.isNotBlank()) { "Choose a pull request." }
        if (remove)
            ctx.gql.execute(
                GitUiRemovePullDependency,
                GitUiRemovePullDependency.Variables(page.pull.id, id),
            )
        else
            ctx.gql.execute(
                GitUiAddPullDependency,
                GitUiAddPullDependency.Variables(page.pull.id, id),
            )
        nextHref = page.href
    }

    private suspend fun perform(block: suspend () -> Unit) {
        message = ""
        failed = false
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Pull request action failed."
        }
    }
}

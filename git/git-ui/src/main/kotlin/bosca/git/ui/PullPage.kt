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
    val repositoryName: String = "",
    val sourceRepositoryName: String = "",
    val repositoryNames: Map<String, String> = emptyMap(),
    val diff: List<DiffView> = emptyList(),
    val diffUnavailableMessage: String = "",
) {
    val canReview: Boolean
        get() = pull.status.name == "OPEN" && diffUnavailableMessage.isBlank()

    val hasMergeBlockers: Boolean
        get() = !pull.mergeable

    val mergeIcon: String
        get() = when (pull.status.name) {
            "MERGED" -> "merge"
            "CLOSED" -> "close"
            "DRAFT" -> "pull"
            else -> if (hasMergeBlockers) "close" else "check"
        }

    val mergeHeading: String
        get() = when (pull.status.name) {
            "MERGED" -> "Pull request merged"
            "CLOSED" -> "Pull request closed"
            "DRAFT" -> "Draft pull request"
            else -> when {
                pull.conflictingFiles.isNotEmpty() -> "Resolve merge conflicts"
                hasMergeBlockers -> "Merge unavailable"
                else -> "No merge conflicts"
            }
        }

    val mergeDescription: String
        get() = when (pull.status.name) {
            "MERGED" -> "These changes have been merged into ${pull.targetBranch}."
            "CLOSED" -> "This pull request was closed without merging."
            "DRAFT" -> "Mark this pull request ready when you want a review."
            else -> when {
                pull.conflictingFiles.isNotEmpty() ->
                    "Resolve these files locally and push the changes to your source branch."
                hasMergeBlockers -> "Check that the source and target branches are available."
                else -> "Required reviews and checks must pass before merging."
            }
        }

    val checkSummary: String
        get() = when {
            checks.isEmpty() -> "No checks reported"
            checks.any { it.state.name in setOf("FAILURE", "ERROR") } -> "Checks failed"
            checks.any { it.state.name == "PENDING" } -> "Checks running"
            else -> "All checks passed"
        }

    val checkTone: String
        get() = when {
            checks.isEmpty() -> "neutral"
            checks.any { it.state.name in setOf("FAILURE", "ERROR") } -> "error"
            checks.any { it.state.name == "PENDING" } -> "warning"
            else -> "success"
        }

    val tabs: List<NavigationItem>
        get() =
            listOf("overview" to "Overview", "files" to "Files Changed", "reviews" to "Reviews")
                .map {
                    NavigationItem(it.second, "$href&view=${it.first}", view == it.first)
                }

    val sourceRepository: String
        get() = if (pull.status == GitPullRequestStatus.MERGED) pull.repositoryId
            else pull.sourceRepositoryId ?: pull.repositoryId

    fun identity(id: String): String = identities[id] ?: id

    fun repositoryLabel(id: String): String = repositoryNames[id] ?: "Repository"

    fun fileHref(path: String, line: Int?): String =
        repositoryHref(sourceRepository, headSha, file = path) + (line?.let { "&line=$it" } ?: "")

    /** Opens the discussion in Files Changed, including discussions outside the current diff. */
    fun discussionHref(thread: PullReviewThread): String = "$href&view=files#${thread.anchor}"

    /** Opens the source at the discussion's original commit and line. */
    fun discussionSourceHref(thread: PullReviewThread): String =
        repositoryHref(pull.sourceRepositoryId ?: pull.repositoryId, thread.first.commitSha.ifBlank { headSha }, file = thread.first.filePath) +
            if (thread.line > 0) "&line=${thread.line}" else ""
}

package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class PullReviewModel(
    val page: PullPage,
    var diff: List<DiffView> = page.pull.diff.map { it.view() },
    var offset: Int = 2000,
    var reviews: List<GitUiPullData.Git.PullRequest.Reviews> = page.pull.reviews,
    var reviewBody: String = "",
    var reviewStatus: String = "COMMENT_ONLY",
    var reviewSubmitted: Boolean = false,
    var reviewId: String = "",
    var commentFile: String = "",
    var oldLine: Int? = null,
    var newLine: Int? = null,
    var commentBody: String = "",
    var message: String = "",
    var failed: Boolean = false,
    var reviewFailed: Boolean = false,
    var reviewing: Boolean = false,
) {
    val files: List<DiffTreeEntry>
        get() = changedFileTree(diff)

    val reviewSummary: String
        get() = "${reviews.size} " + if (reviews.size == 1) "review" else "reviews"

    val fileSummary: String
        get() = "${diff.size} " + if (diff.size == 1) "changed file" else "changed files"

    val viewSummary: String
        get() = if (page.view == "reviews") reviewSummary else fileSummary

    fun lineComments(
        file: String,
        old: Int?,
        new: Int?,
    ): List<GitUiPullData.Git.PullRequest.Reviews.Comments> =
        reviews
            .flatMap { it.comments }
            .filter {
                !it.outdated &&
                    it.filePath == file &&
                    (if (it.newLineNumber != null) it.newLineNumber == new
                    else it.oldLineNumber == old)
            }

    val truncated: Boolean
        get() = diff.any { it.truncated }

    val ownReviews: List<GitUiPullData.Git.PullRequest.Reviews>
        get() = reviews.filter { it.reviewerId in page.currentProfileIds && it.dismissedAt == null }

    suspend fun review(status: String, body: String, ctx: RenderContext) {
        reviewSubmitted = false
        reviewStatus = status
        reviewBody = body
        perform {
            val result =
                ctx.gql
                    .execute(
                        GitUiReview,
                        GitUiReview.Variables(
                            SubmitGitReviewInput(
                                page.pull.id,
                                GitReviewStatus.valueOf(status),
                                body.ifBlank { null },
                            )
                        ),
                    )
                    .git
                    .submitReview
            reviewId = result.id
            reloadReviews(ctx)
            reviewBody = ""
            reviewSubmitted = true
            reviewing = false
            message = "Review submitted."
        }
        reviewFailed = failed
    }

    /** Selects a displayed diff line; zero represents an absent side in the primitive action arguments. */
    suspend fun select(file: String, oldLine: Int, newLine: Int) {
        val old = oldLine.takeIf { it > 0 }
        val new = newLine.takeIf { it > 0 }
        require(
            diff.any {
                it.path == file &&
                    it.hunks.any { h ->
                        h.lines.any { line ->
                            line.oldLineNumber == old && line.newLineNumber == new
                        }
                    }
            }
        ) {
            "Choose a displayed diff line."
        }
        commentFile = file
        this.oldLine = old
        this.newLine = new
        reviewId = ownReviews.lastOrNull()?.id.orEmpty()
    }

    fun cancelComment() {
        commentFile = ""
        commentBody = ""
        message = ""
        failed = false
    }

    fun openReview() {
        reviewing = true
        reviewSubmitted = false
    }

    suspend fun comment(reviewId: String, body: String, ctx: RenderContext) {
        commentBody = body
        this.reviewId = reviewId
        perform {
            require(commentFile.isNotBlank() && (oldLine != null || newLine != null)) {
                "Choose a diff line."
            }
            require(body.isNotBlank()) { "Enter a comment." }
            require(ownReviews.any { it.id == reviewId }) {
                "Submit your own review before commenting."
            }
            requireCurrentHead(ctx)
            ctx.gql.execute(
                GitUiReviewComment,
                GitUiReviewComment.Variables(
                    AddGitReviewCommentInput(
                        reviewId,
                        page.pull.id,
                        commentFile,
                        oldLine,
                        newLine,
                        page.headSha,
                        body,
                    )
                ),
            )
            reloadReviews(ctx)
            commentFile = ""
            commentBody = ""
            message = "Comment added."
        }
    }

    suspend fun resolve(file: String, line: Int, ctx: RenderContext) = perform {
        ctx.gql.execute(GitUiResolveThread, GitUiResolveThread.Variables(page.pull.id, file, line))
        reloadReviews(ctx)
        message = "Thread resolved."
    }

    suspend fun expand(ctx: RenderContext) = perform {
        requireCurrentHead(ctx)
        val next =
            ctx.gql
                .execute(
                    GitUiPullDiffPage,
                    GitUiPullDiffPage.Variables(page.pull.repositoryId, page.pull.number, offset),
                )
                .git
                .pullRequest
                ?.diff
                ?.map { it.view() } ?: error("Pull request unavailable.")
        requireCurrentHead(ctx)
        require(next.size == diff.size) { "The diff changed. Reload this pull request." }
        diff = diff.mapIndexed { index, file ->
            val additional = next[index]
            require(file.path == additional.path && file.hunks.size == additional.hunks.size) {
                "The diff changed. Reload this pull request."
            }
            file.copy(
                hunks =
                    file.hunks.mapIndexed { h, previous ->
                        previous.copy(lines = previous.lines + additional.hunks[h].lines)
                    }
            )
        }
        offset += 2000
    }

    private suspend fun requireCurrentHead(ctx: RenderContext) {
        val current = ctx.gql.commitShaForRef(page.sourceRepository, page.pull.sourceBranch)
        require(current == page.headSha) {
            "The source branch changed. Reload this pull request before continuing."
        }
    }

    private suspend fun reloadReviews(ctx: RenderContext) {
        reviews =
            ctx.gql
                .execute(
                    GitUiPull,
                    GitUiPull.Variables(page.pull.repositoryId, page.pull.number, 1),
                )
                .git
                .pullRequest
                ?.reviews ?: error("Pull request unavailable.")
    }

    private suspend fun perform(block: suspend () -> Unit) {
        failed = false
        reviewFailed = false
        message = ""
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Review action failed."
        }
    }
}

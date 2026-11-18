package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class PullReviewModel(
    val page: PullPage,
    var diff: List<DiffView> = page.diff,
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
    var commentFailed: Boolean = false,
    var commentOpenSequence: Int = 0,
    var replyToCommentId: String = "",
    var message: String = "",
    var noticeSequence: Int = 0,
    var failed: Boolean = false,
    var reviewFailed: Boolean = false,
    var reviewing: Boolean = false,
    var contextFile: String = "",
    var contextMessage: String = "",
) {
    val files: List<DiffTreeEntry>
        get() = changedFileTree(diff)

    val reviewSummary: String
        get() = "${reviews.size} " + if (reviews.size == 1) "review" else "reviews"

    val fileSummary: String
        get() = "${diff.size} " + if (diff.size == 1) "changed file" else "changed files"

    val viewSummary: String
        get() = if (page.view == "reviews") reviewSummary else fileSummary

    @kotlinx.serialization.Transient
    private var indexedReviews: List<GitUiPullData.Git.PullRequest.Reviews>? = null

    @kotlinx.serialization.Transient
    private var threadsByLine: Map<Triple<String, Boolean, Int>, List<PullReviewThread>> = emptyMap()

    /** Index discussions once per review snapshot, including after an action refreshes reviews. */
    fun lineThreads(file: String, old: Int?, new: Int?): List<PullReviewThread> {
        if (indexedReviews !== reviews) {
            threadsByLine = reviews.flatMap(::reviewThreads)
                .filter { sharesDisplayedCommit(it) && !it.outdated && it.hasLine }
                .groupBy { Triple(it.first.filePath, it.first.newLineNumber != null, it.line) }
            indexedReviews = reviews
        }
        return threadsByLine[Triple(file, true, new ?: 0)].orEmpty() +
            threadsByLine[Triple(file, false, old ?: 0)].orEmpty()
    }

    private fun sharesDisplayedCommit(thread: PullReviewThread): Boolean =
        page.selectedCommit == null && (page.pull.status != GitPullRequestStatus.MERGED || thread.first.commitSha == page.headSha)

    /** Discussions that cannot be placed on a displayed line remain reachable below the diff. */
    val detachedThreads: List<PullReviewThread>
        get() = reviews.flatMap(::reviewThreads).filter { thread ->
            !sharesDisplayedCommit(thread) || diff.none { file ->
                file.hunks.any { hunk ->
                    hunk.displayLines.any { line ->
                        thread.matches(file.path, line.oldLineNumber, line.newLineNumber)
                    }
                }
            }
        }

    fun reviewThreads(review: GitUiPullData.Git.PullRequest.Reviews): List<PullReviewThread> =
        review.comments
            .groupBy {
                listOf(
                    it.filePath,
                    it.oldLineNumber?.toString(),
                    it.newLineNumber?.toString(),
                    it.commitSha,
                    it.outdated.toString(),
                )
            }
            .values.map { comments ->
                PullReviewThread(comments.sortedBy { java.time.OffsetDateTime.parse(it.created).toInstant() })
            }

    val isReply: Boolean
        get() = replyToCommentId.isNotBlank()

    val truncated: Boolean
        get() = diff.any { it.truncated }

    val ownReviews: List<GitUiPullData.Git.PullRequest.Reviews>
        get() = reviews.filter { it.reviewerId in page.currentProfileIds && it.dismissedAt == null }

    val commentAnchor: String
        get() = if (isReply) "reply:$replyToCommentId" else "$commentFile:${oldLine ?: 0}:${newLine ?: 0}"

    val commentLocation: String
        get() = commentFile + ((newLine ?: oldLine)?.takeIf { it > 0 }?.let { ":$it" } ?: "")

    suspend fun review(status: String, body: String, ctx: RenderContext) {
        reviewSubmitted = false
        reviewStatus = status
        reviewBody = body
        perform {
            requireCurrentHead(ctx)
            val result =
                ctx.gql
                    .execute(
                        GitUiReview,
                        GitUiReview.Variables(
                            SubmitGitReviewInput(
                                page.pull.id,
                                GitReviewStatus.valueOf(status),
                                body.ifBlank { null },
                                page.pull.version,
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
        require(page.canCommentOnDiff) { "Open all changes to add a line comment." }
        val old = oldLine.takeIf { it > 0 }
        val new = newLine.takeIf { it > 0 }
        require(
            diff.any {
                it.path == file &&
                    it.hunks.any { h ->
                        h.displayLines.any { line ->
                            line.oldLineNumber == old && line.newLineNumber == new
                        }
                    }
            }
        ) {
            "Choose a displayed diff line."
        }
        if (isReply || commentFile != file || this.oldLine != old || this.newLine != new) commentBody = ""
        replyToCommentId = ""
        commentFile = file
        this.oldLine = old
        this.newLine = new
        reviewId = ownReviews.lastOrNull()?.id.orEmpty()
        commentOpenSequence += 1
        failed = false
        commentFailed = false
        message = ""
    }

    fun cancelComment() {
        replyToCommentId = ""
        commentFile = ""
        commentBody = ""
        message = ""
        failed = false
        commentFailed = false
    }

    fun openReview() {
        reviewing = true
        reviewSubmitted = false
    }

    fun reply(commentId: String) {
        val original = reviews.flatMap { it.comments }.firstOrNull { it.id == commentId }
            ?: error("Comment is no longer available. Reload this pull request.")
        if (replyToCommentId != commentId) commentBody = ""
        replyToCommentId = commentId
        commentFile = original.filePath
        oldLine = original.oldLineNumber
        newLine = original.newLineNumber
        commentOpenSequence += 1
        failed = false
        commentFailed = false
        message = ""
    }

    suspend fun comment(body: String, ctx: RenderContext) {
        commentBody = body
        perform {
            require(body.isNotBlank()) { if (isReply) "Enter a reply." else "Enter a comment." }
            val replying = isReply
            if (replying) {
                ctx.gql.execute(
                    GitUiReplyComment,
                    GitUiReplyComment.Variables(page.pull.id, replyToCommentId, body, page.pull.version),
                )
            } else {
                require(page.canCommentOnDiff) { "Open all changes to add a line comment." }
                require(commentFile.isNotBlank() && (oldLine != null || newLine != null)) {
                    "Choose a diff line."
                }
                reviewId = ownReviews.lastOrNull()?.id ?: error("Submit a review before commenting.")
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
                            page.pull.version,
                        )
                    ),
                )
            }
            reloadReviews(ctx)
            commentFile = ""
            commentBody = ""
            replyToCommentId = ""
            message = if (replying) "Reply added." else "Comment added."
        }
        commentFailed = failed
    }

    suspend fun resolve(file: String, line: Int, ctx: RenderContext) = perform {
        ctx.gql.execute(GitUiResolveThread, GitUiResolveThread.Variables(page.pull.id, file, line))
        reloadReviews(ctx)
        message = "Thread resolved."
    }

    suspend fun expand(ctx: RenderContext) = perform {
        requireCurrentHead(ctx)
        val pull =
            ctx.gql
                .execute(
                    GitUiPullDiffPage,
                    GitUiPullDiffPage.Variables(page.pull.repositoryId, page.pull.number, offset, page.selectedCommit?.sha),
                )
                .git
                .pullRequest ?: error("Pull request unavailable.")
        require(pull.version == page.pull.version) { "Pull request changed. Reload it before continuing." }
        val next = pull.diff.map { it.view() }
        requireCurrentHead(ctx)
        require(next.size == diff.size) { "The diff changed. Reload this pull request." }
        diff = diff.mapIndexed { index, file ->
            val additional = next[index]
            require(file.path == additional.path && file.hunks.count { !it.synthetic } == additional.hunks.size) {
                "The diff changed. Reload this pull request."
            }
            // Revealed context-only hunks have no counterpart in the API's list.
            var h = 0
            file.copy(
                hunks =
                    file.hunks.map { previous ->
                        if (previous.synthetic) previous
                        else previous.copy(lines = previous.lines + additional.hunks[h++].lines)
                    }
            )
        }
        offset += 2000
    }

    /** Reveals unchanged lines of [file] above hunk [index] (or after the last hunk) in [direction]. */
    suspend fun expandContext(file: String, index: Int, direction: String, ctx: RenderContext) =
        updateContext(file, ctx) { view, source -> view.expandGap(index, direction, source) }

    /** Reveals every unchanged line of [file]. */
    suspend fun expandFile(file: String, ctx: RenderContext) =
        updateContext(file, ctx) { view, source -> view.expandAll(source) }

    /** Hides the unchanged lines revealed in [file]. */
    fun collapseContext(file: String) {
        contextFile = ""
        contextMessage = ""
        diff = diff.map { if (it.path == file) it.collapseRevealed() else it }
    }

    private suspend fun updateContext(
        file: String,
        ctx: RenderContext,
        change: (DiffView, List<String>) -> DiffView,
    ) {
        contextFile = file
        contextMessage = ""
        try {
            diff = diff.withRevealedLines(file, { ctx.gql.diffSourceLines(page.sourceRepository, page.diffHeadSha, it) }, change)
            contextFile = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            contextMessage = e.message ?: "Unchanged lines could not be shown."
        }
    }

    private suspend fun requireCurrentHead(ctx: RenderContext) {
        if (page.pull.status == GitPullRequestStatus.MERGED) return
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
                    GitUiPull.Variables(page.pull.id, false),
                )
                .git
                .pullRequest
                ?.reviews ?: error("Pull request unavailable.")
    }

    private suspend fun perform(block: suspend () -> Unit) {
        failed = false
        reviewFailed = false
        commentFailed = false
        message = ""
        try {
            block()
            if (message.isNotBlank()) noticeSequence += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Review action failed."
        }
    }
}

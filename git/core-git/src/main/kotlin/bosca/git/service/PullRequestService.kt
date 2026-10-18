package bosca.git.service

import bosca.git.model.GitHubPullRequestSnapshot
import bosca.serialization.OffsetDateTime

import bosca.git.model.CreatePullRequestInput
import bosca.git.model.MergeResult
import bosca.git.model.MergeStrategy
import bosca.git.model.PullRequest
import bosca.git.model.PullRequestStatus
import bosca.git.model.Review
import bosca.git.model.ReviewComment
import bosca.git.model.SubmitReviewInput
import bosca.git.model.UpdatePullRequestInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages the pull request lifecycle: creation with auto-incrementing numbers,
 * updates, server-side merge with branch protection enforcement, review
 * submission, and status transitions.
 */
interface PullRequestService : Service {

    /**
     * Applies observed counterpart metadata only if [expectedVersion] is still current.
     * A completed merge records existing history; this operation never creates commits or updates refs.
     * Returns null on a concurrent edit. MERGED is terminal and cannot be reverted or rewritten.
     * [mergedById] is recorded as the merger and attributes only the resulting MERGED activity.
     */
    suspend fun synchronize(
        id: UUID,
        expectedVersion: Long,
        snapshot: GitHubPullRequestSnapshot,
        mergedAt: OffsetDateTime? = null,
        mergedById: UUID? = null,
    ): PullRequest?

    /**
     * Creates a new pull request with an auto-incrementing number within the repository.
     * Saves the requested branch cleanup choice, or the repository default when omitted.
     */
    suspend fun create(input: CreatePullRequestInput, authorId: UUID): PullRequest

    /**
     * Updates metadata and optionally the source/target branches of an open or draft request.
     * Branch cleanup can be changed before merging; omitted values preserve the saved choice.
     * Branch changes dismiss existing review decisions and mark diff comments outdated atomically.
     * A supplied expected version rejects stale edits. Branch names must resolve to existing commits.
     */
    suspend fun update(id: UUID, input: UpdatePullRequestInput): PullRequest

    /**
     * Retrieves a pull request by its unique identifier.
     */
    suspend fun findById(id: UUID): PullRequest?

    /**
     * Retrieves a pull request by its repository-scoped number.
     */
    suspend fun findByNumber(repositoryId: UUID, number: Int): PullRequest?

    /**
     * Queries pull requests for a repository with optional status and author filters.
     */
    suspend fun findByRepository(
        repositoryId: UUID,
        status: PullRequestStatus? = null,
        authorId: UUID? = null,
        offset: Long = 0,
        limit: Int = 25
    ): List<PullRequest>

    /**
     * Queries pull requests across the supplied repositories with optional status and author filters.
     */
    suspend fun findByRepositories(
        repositoryIds: List<UUID>,
        status: PullRequestStatus? = null,
        authorId: UUID? = null,
        offset: Long = 0,
        limit: Int = 25
    ): List<PullRequest>

    /**
     * Returns open pull requests whose source branch matches [sourceBranch] in [repositoryId].
     * Used by ref-update handling to invalidate stale reviews and publish PR update events.
     */
    suspend fun findOpenBySourceBranch(repositoryId: UUID, sourceBranch: String): List<PullRequest>

    /** Returns the pull requests that must be merged before [pullRequestId]. */
    suspend fun getDependencies(pullRequestId: UUID): List<PullRequest>

    /** Returns the pull requests that directly depend on [pullRequestId]. */
    suspend fun getDependents(pullRequestId: UUID): List<PullRequest>

    /**
     * Adds a blocking dependency from [pullRequestId] to [dependencyId]. Self-links,
     * duplicate links, and links that would create a dependency cycle are rejected.
     */
    suspend fun addDependency(pullRequestId: UUID, dependencyId: UUID): PullRequest

    /** Removes a direct dependency from [pullRequestId] to [dependencyId]. */
    suspend fun removeDependency(pullRequestId: UUID, dependencyId: UUID): PullRequest

    /**
     * Returns every unresolved pull request required for merging [pullRequestId], in
     * deterministic dependency-first order with [pullRequestId] last.
     */
    suspend fun getMergePlan(pullRequestId: UUID): List<PullRequest>

    /**
     * Merges a pull request using the given strategy. Enforces branch protection
     * rules (required approvals, status checks) before performing the merge.
     * Updates the target branch ref on success and deletes the local source branch when enabled on the PR,
     * unless it is the default branch, protected, or still used by an open or draft PR.
     * Owns its transaction independently of the caller. Locks and rechecks the PR version
     * before writing; merge metadata, ref updates/deletion, and event registration commit together.
     * Holds the repository write lock through commit. Cancellation rolls back the ref and PR.
     * A supplied [expectedVersion] rejects a merge based on a previous PR comparison.
     * [bypassStatusChecks] skips only required CI checks; the API must authorize the administrator override.
     */
    suspend fun merge(
        id: UUID,
        strategy: MergeStrategy,
        mergedById: UUID,
        mergerName: String,
        mergerEmail: String,
        expectedVersion: Long? = null,
        bypassStatusChecks: Boolean = false,
    ): PullRequest

    /**
     * Verifies the current PR version, open state, dependencies, source SHA, reviews and status checks
     * before importing an already completed merge. Does not create history or update a ref.
     * The caller holds the repository write lock and transaction through the subsequent import.
     */
    suspend fun verifyMergeAllowed(id: UUID, expectedVersion: Long, sourceSha: String)

    /**
     * Preflights and merges all unresolved dependencies before [id]. Already-merged
     * dependencies are skipped. [expectedPullRequestIds] must match the current
     * dependency-first plan so an authorization decision cannot be reused after the graph changes.
     * The returned list is in the order merged.
     * A supplied [expectedVersion] verifies the root PR before merging any dependencies.
     * [bypassStatusChecks] applies the authorized CI override to every PR in the plan.
     */
    suspend fun mergeWithDependencies(
        id: UUID,
        strategy: MergeStrategy,
        mergedById: UUID,
        mergerName: String,
        mergerEmail: String,
        expectedPullRequestIds: List<UUID>,
        expectedVersion: Long? = null,
        bypassStatusChecks: Boolean = false,
    ): List<PullRequest>

    /** Returns required check contexts missing a successful result on the current source head. */
    suspend fun getFailingStatusChecks(id: UUID): List<String>

    /**
     * Checks whether a PR can be cleanly merged without actually performing the merge.
     */
    suspend fun checkMergeability(id: UUID): MergeResult

    /**
     * Closes an open or draft pull request without merging.
     */
    suspend fun close(id: UUID): PullRequest

    /**
     * Reopens a previously closed pull request.
     */
    suspend fun reopen(id: UUID): PullRequest

    /**
     * Transitions a draft pull request to open status.
     */
    suspend fun markReady(id: UUID): PullRequest

    /**
     * Submits a review (approve, request changes, or comment) on an open pull request.
     * A supplied expected version rejects decisions based on a previous branch comparison.
     */
    suspend fun submitReview(input: SubmitReviewInput, reviewerId: UUID): Review

    /**
     * Retrieves all reviews submitted for a pull request.
     */
    suspend fun getReviews(pullRequestId: UUID): List<Review>

    /**
     * Adds a comment anchored to a specific diff line in a pull request review.
     * A supplied [expectedVersion] rejects comments based on a previous branch comparison.
     */
    suspend fun addReviewComment(
        reviewId: UUID,
        pullRequestId: UUID,
        authorId: UUID,
        filePath: String,
        oldLineNumber: Int?,
        newLineNumber: Int?,
        commitSha: String,
        content: String,
        expectedVersion: Long? = null,
    ): ReviewComment

    /**
     * Replies under the original comment's review and immutable diff context.
     * Outdated anchors are retained; a new unresolved reply reopens a resolved discussion.
     * A supplied [expectedVersion] rejects replies from a stale pull-request page.
     */
    suspend fun replyReviewComment(
        pullRequestId: UUID,
        commentId: UUID,
        authorId: UUID,
        content: String,
        expectedVersion: Long?,
    ): ReviewComment

    /**
     * Retrieves all review comments for a pull request across all reviews.
     */
    suspend fun getCommentsForPullRequest(pullRequestId: UUID): List<ReviewComment>

    /**
     * Resolves all comments in a thread identified by file path and line number.
     */
    suspend fun resolveThread(pullRequestId: UUID, filePath: String, lineNumber: Int)

    /**
     * Dismisses active reviews and marks comments as outdated when the source
     * branch is force-pushed. Only dismisses if the matching branch protection
     * rule has dismiss-stale-reviews enabled.
     */
    suspend fun onSourceBranchPushed(pullRequestId: UUID, newSourceSha: String)

    /**
     * Returns the profile IDs of all users assigned to a pull request.
     */
    suspend fun getAssignees(pullRequestId: UUID): List<UUID>

    /**
     * Assigns a profile to a pull request. Idempotent — assigning the same
     * profile twice has no effect.
     */
    suspend fun addAssignee(pullRequestId: UUID, profileId: UUID)

    /**
     * Removes a profile from a pull request's assignee list.
     */
    suspend fun removeAssignee(pullRequestId: UUID, profileId: UUID)
}

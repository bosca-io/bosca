package bosca.git.service

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
     * Creates a new pull request with an auto-incrementing number within the repository.
     */
    suspend fun create(input: CreatePullRequestInput, authorId: UUID): PullRequest

    /**
     * Updates the title and/or description of an existing pull request.
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
     * Updates the target branch ref on success.
     */
    suspend fun merge(
        id: UUID,
        strategy: MergeStrategy,
        mergedById: UUID,
        mergerName: String,
        mergerEmail: String
    ): PullRequest

    /**
     * Preflights and merges all unresolved dependencies before [id]. Already-merged
     * dependencies are skipped. [expectedPullRequestIds] must match the current
     * dependency-first plan so an authorization decision cannot be reused after the graph changes.
     * The returned list is in the order merged.
     */
    suspend fun mergeWithDependencies(
        id: UUID,
        strategy: MergeStrategy,
        mergedById: UUID,
        mergerName: String,
        mergerEmail: String,
        expectedPullRequestIds: List<UUID>
    ): List<PullRequest>

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
     * Submits a review (approve, request changes, or comment) on a pull request.
     */
    suspend fun submitReview(input: SubmitReviewInput, reviewerId: UUID): Review

    /**
     * Retrieves all reviews submitted for a pull request.
     */
    suspend fun getReviews(pullRequestId: UUID): List<Review>

    /**
     * Adds a comment anchored to a specific diff line in a pull request review.
     */
    suspend fun addReviewComment(
        reviewId: UUID,
        pullRequestId: UUID,
        authorId: UUID,
        filePath: String,
        oldLineNumber: Int?,
        newLineNumber: Int?,
        commitSha: String,
        content: String
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

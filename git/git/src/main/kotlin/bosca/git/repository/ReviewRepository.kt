package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.Review
import bosca.serialization.UUID

/**
 * Database access layer for pull request reviews in `git.reviews`.
 */
@Repository
interface ReviewRepository {

    @Query("select * from git.reviews where pull_request_id = :pullRequestId order by created")
    suspend fun findByPullRequest(pullRequestId: UUID): List<Review>

    @Query("select * from git.reviews where id = :id")
    suspend fun findById(id: UUID): Review?

    @Query("""
        insert into git.reviews (pull_request_id, reviewer_id, status, body)
        values (:pullRequestId, :reviewerId, :status::git.review_status, :body)
        returning *
    """)
    suspend fun create(review: Review): Review

    /** Active approving reviews; dismissed approvals never satisfy a merge requirement. */
    @Query("""
        select * from git.reviews
        where pull_request_id = :pullRequestId and status = 'approved' and dismissed_at is null
    """)
    suspend fun findApprovedByPullRequest(pullRequestId: UUID): List<Review>

    /** The latest unresolved change request; dismissed reviews no longer block a merge. */
    @Query("""
        select * from git.reviews
        where pull_request_id = :pullRequestId and status = 'changes_requested' and dismissed_at is null
        order by created desc limit 1
    """)
    suspend fun findLatestChangesRequested(pullRequestId: UUID): Review?

    @Query("""
        update git.reviews set dismissed_at = now(), dismiss_reason = :reason
        where pull_request_id = :pullRequestId and dismissed_at is null
        and status in ('approved', 'changes_requested')
    """)
    suspend fun dismissByPullRequest(pullRequestId: UUID, reason: String)

    @Query("""
        select * from git.reviews
        where pull_request_id = :pullRequestId and dismissed_at is null
        order by created
    """)
    suspend fun findActiveByPullRequest(pullRequestId: UUID): List<Review>
}

package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.ReviewComment
import bosca.serialization.UUID

/**
 * Database access layer for review comments anchored to specific diff lines
 * in pull request reviews.
 */
@Repository
interface ReviewCommentRepository {

    @Query("select * from git.review_comments where id = :id")
    suspend fun findById(id: UUID): ReviewComment?

    @Query("select * from git.review_comments where review_id = :reviewId order by created")
    suspend fun findByReview(reviewId: UUID): List<ReviewComment>

    @Query("select * from git.review_comments where pull_request_id = :pullRequestId order by file_path, new_line_number")
    suspend fun findByPullRequest(pullRequestId: UUID): List<ReviewComment>

    @Query("""
        insert into git.review_comments (review_id, pull_request_id, author_id, file_path, old_line_number, new_line_number, commit_sha, content)
        values (:reviewId, :pullRequestId, :authorId, :filePath, :oldLineNumber, :newLineNumber, :commitSha, :content)
        returning *
    """)
    suspend fun create(comment: ReviewComment): ReviewComment

    @Query("""
        update git.review_comments set outdated = true, updated = now()
        where pull_request_id = :pullRequestId and outdated = false and commit_sha != :currentSha
    """)
    suspend fun markOutdatedByPullRequest(pullRequestId: UUID, currentSha: String)

    @Query("""
        update git.review_comments set resolved = true, updated = now() where id = :id returning *
    """)
    suspend fun resolve(id: UUID): ReviewComment?

    @Query("""
        update git.review_comments set resolved = true, updated = now()
        where pull_request_id = :pullRequestId and file_path = :filePath
        and ((old_line_number = :lineNumber) or (new_line_number = :lineNumber))
    """)
    suspend fun resolveThread(pullRequestId: UUID, filePath: String, lineNumber: Int)
}

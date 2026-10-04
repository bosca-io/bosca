package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.PullRequest
import bosca.git.model.PullRequestStatus
import bosca.serialization.UUID

/** Parameters for querying pull requests across a permission-filtered repository set. */
data class PullRequestRepositoryQuery(
    val repositoryIds: List<UUID>,
    val status: PullRequestStatus?,
    val authorId: UUID?,
    val offset: Long,
    val limit: Int
)

/**
 * Database access layer for pull requests in `git.pull_requests`. PR numbers
 * are auto-incremented per-repository via the `next_pr_number` column on
 * `git.repositories`.
 */
@Repository
interface PullRequestRepository {

    @Query("select * from git.pull_requests where id = :id")
    suspend fun findById(id: UUID): PullRequest?

    @Query("select * from git.pull_requests where id = :id for update")
    suspend fun lockById(id: UUID): PullRequest?

    @Query("select * from git.pull_requests where repository_id = :repositoryId and number = :number")
    suspend fun findByNumber(repositoryId: UUID, number: Int): PullRequest?

    @Query("""
        select * from git.pull_requests
        where repository_id = :repositoryId
        order by created desc
        limit :limit offset :offset
    """)
    suspend fun findByRepository(repositoryId: UUID, offset: Long, limit: Int): List<PullRequest>

    @Query("""
        select * from git.pull_requests
        where repository_id = :repositoryId and status = :status::git.pull_request_status
        order by created desc
        limit :limit offset :offset
    """)
    suspend fun findByRepositoryAndStatus(repositoryId: UUID, status: PullRequestStatus, offset: Long, limit: Int): List<PullRequest>

    @Query("""
        select * from git.pull_requests
        where repository_id = :repositoryId and author_id = :authorId
        order by created desc
        limit :limit offset :offset
    """)
    suspend fun findByAuthor(repositoryId: UUID, authorId: UUID, offset: Long, limit: Int): List<PullRequest>

    @Query("""
        select * from git.pull_requests
        where repository_id = any(:repositoryIds)
          and (:status::git.pull_request_status is null or status = :status::git.pull_request_status)
          and (:authorId::uuid is null or author_id = :authorId::uuid)
        order by updated desc, id desc
        limit :limit offset :offset
    """)
    suspend fun findByRepositories(query: PullRequestRepositoryQuery): List<PullRequest>

    @Query("""
        insert into git.pull_requests (repository_id, number, title, description, author_id, source_branch, target_branch, source_repository_id, status)
        values (:repositoryId, :number, :title, :description, :authorId, :sourceBranch, :targetBranch, :sourceRepositoryId, :status::git.pull_request_status)
        returning *
    """)
    suspend fun create(pr: PullRequest): PullRequest

    @Query("""
        update git.pull_requests
        set title = :title, description = :description, updated = now(), version = version + 1
        where id = :id and version = :version
        returning *
    """)
    suspend fun update(pr: PullRequest): PullRequest?

    @Query("""
        update git.pull_requests
        set status = :status::git.pull_request_status, merge_strategy = :mergeStrategy::git.merge_strategy,
            merged_by = :mergedBy, merged_at = :mergedAt, merge_sha = :mergeSha, updated = now(), version = version + 1
        where id = :id and version = :version
        returning *
    """)
    suspend fun updateMergeState(pr: PullRequest): PullRequest?

    @Query("""
        update git.pull_requests set status = :status::git.pull_request_status, updated = now(), version = version + 1
        where id = :id and version = :version returning *
    """)
    suspend fun updateStatus(id: UUID, status: PullRequestStatus, version: Long): PullRequest?

    @Query("""
        update git.pull_requests set title = :title, description = :description,
            source_branch = :sourceBranch, target_branch = :targetBranch, status = :status::git.pull_request_status,
            merge_sha = :mergeSha, merged_at = :mergedAt, merged_by = :mergedBy,
            updated = now(), version = version + 1
        where id = :id and version = :version returning *
    """)
    suspend fun synchronize(pr: PullRequest): PullRequest?

    @Query("""
        select * from git.pull_requests
        where repository_id = :repositoryId and source_branch = :sourceBranch and status = 'open'
    """)
    suspend fun findOpenBySourceBranch(repositoryId: UUID, sourceBranch: String): List<PullRequest>
}

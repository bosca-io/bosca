package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.CommitStatus
import bosca.serialization.UUID

/**
 * Database access layer for CI/CD commit status checks that gate pull
 * request merges via branch protection rules.
 */
@Repository
interface CommitStatusRepository {

    @Query("select * from git.commit_statuses where id = :id")
    suspend fun findById(id: UUID): CommitStatus?

    @Query("""
        select * from git.commit_statuses
        where repository_id = :repositoryId and commit_sha = :commitSha
        order by created desc
    """)
    suspend fun findByCommitSha(repositoryId: UUID, commitSha: String): List<CommitStatus>

    @Query("""
        select * from git.commit_statuses
        where repository_id = :repositoryId and commit_sha = :commitSha and context = :context
    """)
    suspend fun findByContext(repositoryId: UUID, commitSha: String, context: String): CommitStatus?

    @Query("""
        insert into git.commit_statuses (repository_id, commit_sha, context, state, description, target_url)
        values (:repositoryId, :commitSha, :context, :state::git.commit_status_state, :description, :targetUrl)
        returning *
    """)
    suspend fun create(status: CommitStatus): CommitStatus

    @Query("""
        update git.commit_statuses
        set state = :state::git.commit_status_state, description = :description, target_url = :targetUrl
        where id = :id returning *
    """)
    suspend fun update(status: CommitStatus): CommitStatus?
}

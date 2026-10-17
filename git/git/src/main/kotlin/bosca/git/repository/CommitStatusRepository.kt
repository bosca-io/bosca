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

    /** Atomically inserts or changes a check; unchanged reports preserve their revision. */
    @Query("""
        insert into git.commit_statuses (repository_id, commit_sha, context, state, description, target_url, ref)
        values (:repositoryId, :commitSha, :context, :state::git.commit_status_state, :description, :targetUrl, :ref)
        on conflict (repository_id, commit_sha, context) do update
        set state = excluded.state, description = excluded.description, target_url = excluded.target_url,
            ref = coalesce(excluded.ref, git.commit_statuses.ref), modified = clock_timestamp()
        where (git.commit_statuses.state, git.commit_statuses.description, git.commit_statuses.target_url,
               git.commit_statuses.ref) is distinct from
              (excluded.state, excluded.description, excluded.target_url, coalesce(excluded.ref, git.commit_statuses.ref))
        returning *
    """)
    suspend fun upsert(status: CommitStatus): CommitStatus?

    /** Pending checks remain eligible even when their most recent update is older than [since]. */
    @Query("""
        select * from git.commit_statuses where repository_id = :repositoryId
          and (modified >= :since or state = 'pending')
        order by modified, id limit :limit offset :offset
    """)
    suspend fun findChangedSince(repositoryId: UUID, since: bosca.serialization.OffsetDateTime, offset: Long, limit: Int): List<CommitStatus>
}

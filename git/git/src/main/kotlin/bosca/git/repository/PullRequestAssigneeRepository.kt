package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Database access layer for the `git.pull_request_assignees` junction table,
 * which tracks which profiles are assigned to a pull request.
 */
@Repository
interface PullRequestAssigneeRepository {

    @Query("select profile_id from git.pull_request_assignees where pull_request_id = :pullRequestId")
    suspend fun findByPullRequest(pullRequestId: UUID): List<UUID>

    @Query("""
        insert into git.pull_request_assignees (pull_request_id, profile_id)
        values (:pullRequestId, :profileId)
        on conflict do nothing
    """)
    suspend fun add(pullRequestId: UUID, profileId: UUID)

    @Query("delete from git.pull_request_assignees where pull_request_id = :pullRequestId and profile_id = :profileId")
    suspend fun remove(pullRequestId: UUID, profileId: UUID)
}

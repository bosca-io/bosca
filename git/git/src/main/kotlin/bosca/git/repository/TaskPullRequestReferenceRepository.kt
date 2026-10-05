package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.TaskPullRequestReference
import bosca.serialization.UUID

/**
 * Database access layer for task-to-pull-request linkages extracted from
 * PR titles, descriptions, and branch names.
 */
@Repository
interface TaskPullRequestReferenceRepository {

    @Query("select * from git.task_pull_request_references where repository_id = :repositoryId and task_key = :taskKey")
    suspend fun findByTaskKey(repositoryId: UUID, taskKey: String): List<TaskPullRequestReference>

    @Query("select * from git.task_pull_request_references where pull_request_id = :pullRequestId")
    suspend fun findByPullRequest(pullRequestId: UUID): List<TaskPullRequestReference>

    @Query("""
        insert into git.task_pull_request_references (repository_id, task_key, pull_request_id, pull_request_number)
        values (:repositoryId, :taskKey, :pullRequestId, :pullRequestNumber)
        on conflict do nothing
        returning *
    """)
    suspend fun create(ref: TaskPullRequestReference): TaskPullRequestReference?
}

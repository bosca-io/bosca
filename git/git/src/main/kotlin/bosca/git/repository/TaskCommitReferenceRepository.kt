package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.TaskCommitReference
import bosca.serialization.UUID

/**
 * Database access layer for task-to-commit linkages extracted from commit
 * messages during push processing.
 */
@Repository
interface TaskCommitReferenceRepository {

    @Query("select * from git.task_commit_references where repository_id = :repositoryId and task_key = :taskKey")
    suspend fun findByTaskKey(repositoryId: UUID, taskKey: String): List<TaskCommitReference>

    @Query("select * from git.task_commit_references where commit_sha = :commitSha")
    suspend fun findByCommitSha(commitSha: String): List<TaskCommitReference>

    @Query("""
        insert into git.task_commit_references (repository_id, task_key, commit_sha)
        values (:repositoryId, :taskKey, :commitSha)
        on conflict do nothing
        returning *
    """)
    suspend fun create(ref: TaskCommitReference): TaskCommitReference?
}

package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Links a task key (e.g. "PROJ-123") extracted from commit messages to the
 * commit that referenced it. Created by the PostReceiveHook after push.
 */
@Serializable
data class TaskCommitReference(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    @ColumnName("task_key") val taskKey: String,
    @ColumnName("commit_sha") val commitSha: String,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now()
)

/**
 * Links a task key extracted from a pull request's title, description, or
 * branch name to the PR. Created by PullRequestService on create/update.
 */
@Serializable
data class TaskPullRequestReference(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    @ColumnName("task_key") val taskKey: String,
    @Contextual @ColumnName("pull_request_id") val pullRequestId: UUID,
    @ColumnName("pull_request_number") val pullRequestNumber: Int,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now()
)

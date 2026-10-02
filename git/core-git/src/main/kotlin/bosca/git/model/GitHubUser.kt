package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Administrator-verified mapping from an immutable GitHub user ID to a Bosca principal. */
@Serializable
data class GitHubUser(
    @ColumnName("github_user_id") val githubUserId: Long,
    @Contextual @ColumnName("principal_id") val principalId: UUID,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val modified: OffsetDateTime = OffsetDateTime.now(),
)

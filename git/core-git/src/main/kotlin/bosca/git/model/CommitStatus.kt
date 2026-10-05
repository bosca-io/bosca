package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * The state of a CI/CD check reported against a specific commit.
 */
@DbMapper(CommitStatusStateMapper::class)
@Serializable
enum class CommitStatusState {
    PENDING,
    SUCCESS,
    FAILURE,
    ERROR
}

object CommitStatusStateMapper : EnumMapper<CommitStatusState>({ CommitStatusState.valueOf(it.uppercase()) })

/**
 * A CI/CD status check reported against a commit SHA. Multiple statuses can exist
 * per commit (one per [context], e.g. "ci/build", "ci/test"). Branch protection
 * rules reference these contexts in [BranchProtectionRule.requireStatusChecks].
 */
@Serializable
data class CommitStatus(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    @ColumnName("commit_sha") val commitSha: String,
    val context: String,
    val state: CommitStatusState,
    val description: String? = null,
    @ColumnName("target_url") val targetUrl: String? = null,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now()
)

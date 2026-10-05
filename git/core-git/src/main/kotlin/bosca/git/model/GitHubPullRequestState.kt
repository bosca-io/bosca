package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Fields shared by paired pull requests. Reviews and comments remain on their original host. */
@Serializable
data class GitHubPullRequestSnapshot(
    val title: String,
    val description: String?,
    val sourceBranch: String,
    val targetBranch: String,
    val status: PullRequestStatus,
    val mergeSha: String? = null,
)

/** Counterpart identities, the content baseline, and current unresolved synchronization problems. */
@Serializable
data class GitHubPullRequestState(
    @Contextual val id: UUID = UUID.random(),
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    @Contextual @ColumnName("pull_request_id") val pullRequestId: UUID? = null,
    @ColumnName("github_id") val githubId: Long? = null,
    @ColumnName("github_number") val githubNumber: Int? = null,
    val imported: Boolean = false,
    /** Agreed content, or the original outbound content reserved before the first provider write. */
    @property:DbMapper(JsonbMapper::class) val snapshot: GitHubPullRequestSnapshot? = null,
    /** Committed before provider mutations, so a partial REST/draft update can resume after failure. */
    @property:DbMapper(JsonbMapper::class) val pending: GitHubPullRequestSnapshot? = null,
    @property:DbMapper(JsonbMapper::class) @ColumnName("bosca") val boscaSnapshot: GitHubPullRequestSnapshot? = null,
    @property:DbMapper(JsonbMapper::class) @ColumnName("github") val githubSnapshot: GitHubPullRequestSnapshot? = null,
    val problem: String? = null,
    @Contextual val modified: OffsetDateTime = OffsetDateTime.now(),
)

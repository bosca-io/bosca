package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** A repository pair uses GitHub's immutable repository ID, including across renames. */
@Serializable
data class GitHubRepositoryPair(
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    @ColumnName("github_repository_id") val githubRepositoryId: Long,
    val owner: String,
    val name: String,
    @ColumnName("webhook_secret_name") val webhookSecretName: String,
    @ColumnName("token_secret_name") val tokenSecretName: String,
    val enabled: Boolean = false,
    val version: Long = 0,
    @ColumnName("push_branch_includes") val pushBranchIncludes: List<String> = emptyList(),
    @ColumnName("push_branch_excludes") val pushBranchExcludes: List<String> = emptyList(),
    @ColumnName("pull_branch_includes") val pullBranchIncludes: List<String> = emptyList(),
    @ColumnName("pull_branch_excludes") val pullBranchExcludes: List<String> = emptyList(),
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val modified: OffsetDateTime = OffsetDateTime.now(),
)

/** Configuration contains secret references; an omitted ID is resolved using the token on creation or rename. */
@Serializable
data class GitHubRepositoryPairInput(
    @Contextual val repositoryId: UUID,
    val githubRepositoryId: Long? = null,
    val owner: String,
    val name: String,
    val webhookSecretName: String,
    val tokenSecretName: String,
    val enabled: Boolean = false,
    val version: Long = 0,
    val pushBranchIncludes: List<String> = emptyList(),
    val pushBranchExcludes: List<String> = emptyList(),
    val pullBranchIncludes: List<String> = emptyList(),
    val pullBranchExcludes: List<String> = emptyList(),
)

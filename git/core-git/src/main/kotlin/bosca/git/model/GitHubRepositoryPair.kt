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
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val modified: OffsetDateTime = OffsetDateTime.now(),
)

/** Configuration contains secret references; plaintext credentials never enter the pairing record. */
@Serializable
data class GitHubRepositoryPairInput(
    @Contextual val repositoryId: UUID,
    val githubRepositoryId: Long,
    val owner: String,
    val name: String,
    val webhookSecretName: String,
    val tokenSecretName: String,
    val enabled: Boolean = false,
    val version: Long = 0,
)

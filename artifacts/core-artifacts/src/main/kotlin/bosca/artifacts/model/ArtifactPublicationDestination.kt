package bosca.artifacts.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** A GitHub release destination for one raw artifact repository, referencing an existing Pipeline secret. */
@Serializable
data class ArtifactPublicationDestination(
    @Contextual val id: UUID,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    val key: String,
    @ColumnName("github_repository_id") val githubRepositoryId: Long,
    val owner: String,
    @ColumnName("github_repository") val githubRepository: String,
    @ColumnName("tag_prefix") val tagPrefix: String,
    val enabled: Boolean,
    @ColumnName("token_secret_name") val tokenSecretName: String,
    val version: Long = 0,
    @Contextual val created: OffsetDateTime? = null,
    @Contextual val modified: OffsetDateTime? = null,
)

/** Target identity and tag naming are fixed at creation; changing targets requires a new destination. */
@Serializable
data class ArtifactPublicationDestinationInput(
    @Contextual val repositoryId: UUID,
    val key: String,
    val githubRepositoryId: Long,
    val owner: String,
    val githubRepository: String,
    val tokenSecretName: String,
    val tagPrefix: String = "",
    val enabled: Boolean = false,
)

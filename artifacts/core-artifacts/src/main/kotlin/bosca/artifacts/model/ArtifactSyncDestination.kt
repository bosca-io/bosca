package bosca.artifacts.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** A GHCR image destination for one Docker repository, using an encrypted Pipeline secret. */
@Serializable
data class ArtifactSyncDestination(
    @Contextual val id: UUID,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    val key: String,
    @ColumnName("remote_repository") val remoteRepository: String,
    val username: String,
    @ColumnName("token_secret_name") val tokenSecretName: String,
    val enabled: Boolean = false,
    val version: Long = 0,
    @Contextual val created: OffsetDateTime? = null,
    @Contextual val modified: OffsetDateTime? = null,
)

/** Configures a remote image path while preserving source tags. */
@Serializable
data class ArtifactSyncDestinationInput(
    @Contextual val repositoryId: UUID,
    val key: String,
    val remoteRepository: String,
    val username: String,
    val tokenSecretName: String,
    val enabled: Boolean = false,
)

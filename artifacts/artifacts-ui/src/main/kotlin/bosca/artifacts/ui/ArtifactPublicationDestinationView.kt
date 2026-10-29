package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactPublicationDestinationView(
    val id: String,
    val key: String,
    val owner: String,
    val githubRepository: String,
    val githubRepositoryId: Long,
    val tagPrefix: String,
    val tokenSecretName: String,
    val enabled: Boolean,
    val version: Long,
)

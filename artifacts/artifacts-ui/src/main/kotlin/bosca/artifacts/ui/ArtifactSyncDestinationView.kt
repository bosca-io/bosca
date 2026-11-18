package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactSyncDestinationView(
    val id: String,
    val key: String,
    val remoteRepository: String,
    val username: String,
    val tokenSecretName: String,
    val enabled: Boolean,
    val version: Long,
)

package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactSyncDraft(
    val id: String = "",
    val version: Long = 0,
    val key: String = "",
    val remoteRepository: String = "",
    val username: String = "",
    val tokenSecretName: String = "",
    val enabled: Boolean = false,
)

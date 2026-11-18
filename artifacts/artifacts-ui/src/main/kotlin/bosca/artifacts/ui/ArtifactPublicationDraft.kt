package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactPublicationDraft(
    val id: String = "",
    val version: Long = 0,
    val key: String = "",
    val owner: String = "",
    val githubRepository: String = "",
    val githubRepositoryId: String = "",
    val tagPrefix: String = "",
    val tokenSecretName: String = "",
    val enabled: Boolean = false,
)

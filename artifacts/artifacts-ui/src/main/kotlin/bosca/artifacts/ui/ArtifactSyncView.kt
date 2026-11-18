package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactSyncView(
    val id: String,
    val destinationId: String,
    val tag: String,
    val digest: String,
    val attempts: Int,
    val synced: String,
    val error: String,
    val modified: String,
)

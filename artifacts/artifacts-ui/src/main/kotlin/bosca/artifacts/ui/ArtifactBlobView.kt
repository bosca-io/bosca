package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactBlobView(
    val digest: String,
    val role: String,
    val filename: String,
    val mediaType: String,
    val size: Long?,
    val download: String = "",
) {
    val sizeLabel: String get() = artifactSize(size)
}

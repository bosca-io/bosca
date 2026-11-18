package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactPublicationView(
    val id: String,
    val destinationId: String,
    val tag: String,
    val commit: String,
    val attempts: Int,
    val published: String,
    val verified: String,
    val verifiedAttempt: Int?,
    val error: String,
    val files: List<ArtifactBlobView>,
) {
    val status: String get() = when {
        error.isNotBlank() -> "Failed"
        verified.isNotBlank() && verifiedAttempt == attempts -> "Verified"
        published.isNotBlank() -> "Published; verification pending"
        else -> "Pending"
    }
}

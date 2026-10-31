package bosca.artifacts.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ArtifactVersionView(
    val id: String,
    val version: String,
    val created: String,
    val completed: Boolean,
    val finalized: Boolean,
    val commitSha: String,
    val blobs: List<ArtifactBlobView>,
    val metadata: JsonElement? = null,
)

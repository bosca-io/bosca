package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactRunNode(val id: String, val status: String, val error: String)

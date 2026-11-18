package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactTagView(val name: String, val digest: String, val modified: String)

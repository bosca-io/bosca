package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactCreateDraft(
    val name: String = "",
    val namespaceId: String = "",
    val type: String = "raw",
    val public: Boolean = false,
)

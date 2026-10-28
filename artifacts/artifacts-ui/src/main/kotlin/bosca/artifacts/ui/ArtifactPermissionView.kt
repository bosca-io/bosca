package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactPermissionView(val groupId: String, val groupName: String, val action: String)

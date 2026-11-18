package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemBibleConfigurationDraft(
    val id: String = "", val name: String = "", val provider: String = "",
    val enabled: Boolean = true, val apiKey: String = "", val hasApiKey: Boolean = false,
)

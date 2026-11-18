package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemConfigurationDraft(
    val key: String = "", val description: String = "", val value: String = "{}", val public: Boolean = false,
)

package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemPersonaDraft(val name: String = "", val description: String = "", val subsystems: List<String> = emptyList(), val enabled: Boolean = true)

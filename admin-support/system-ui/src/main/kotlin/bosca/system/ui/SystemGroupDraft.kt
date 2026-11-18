package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemGroupDraft(val name: String = "", val description: String = "", val type: String = "PRINCIPAL")

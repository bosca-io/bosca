package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemFederationDraft(val name: String = "", val natsUrl: String = "", val apiUrl: String = "", val secret: String = "")

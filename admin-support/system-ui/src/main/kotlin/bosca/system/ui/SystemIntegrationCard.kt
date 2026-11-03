package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemIntegrationCard(val key: String, val name: String, val description: String, val icon: String, val configured: Boolean) {
    val href get() = "/system/integrations/$key"
}

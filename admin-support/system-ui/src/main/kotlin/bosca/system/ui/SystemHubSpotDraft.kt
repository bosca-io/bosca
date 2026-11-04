package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemHubSpotDraft(
    val token: String = "", val clearToken: Boolean = false,
    val associationTypeId: String = "279", val associationCategory: String = "HUBSPOT_DEFINED",
    val listIds: List<String> = emptyList(), val subscriptionIds: List<String> = emptyList(),
    val genericExpression: String = "", val organizationExpression: String = "",
)

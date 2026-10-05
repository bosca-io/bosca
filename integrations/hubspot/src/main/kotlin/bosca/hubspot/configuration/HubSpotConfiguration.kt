package bosca.hubspot.configuration

import kotlinx.serialization.Serializable

@Serializable
data class HubSpotConfiguration(
    val token: String,
    val expressions: HubSpotExpressions,
    val contactToCompanyAssociationTypeId: Int = 279,
    val contactToCompanyAssociationCategory: String = "HUBSPOT_DEFINED",
    val listIds: List<String>? = emptyList(),
    val subscriptionIds: List<String>? = emptyList()
)

@Serializable
data class HubSpotExpressions(
    val generic: String,
    val organization: String
)
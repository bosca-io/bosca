package bosca.profile.organization.model

import kotlinx.serialization.Serializable

@Serializable
data class OrganizationDetails(
    val tradition: String? = null,
    val size: String? = null,
    val country: String? = null,
    val addresses: List<OrganizationAddress>,
    val contacts: List<OrganizationContact>
)
package bosca.profile.organization.model

import kotlinx.serialization.Serializable

@Serializable
data class OrganizationAddress(
    val street1: String,
    val street2: String?,
    val city: String?,
    val region: String?,
    val country: String?,
    val postalCode: String?
)
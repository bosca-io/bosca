package bosca.profile.organization.model

import kotlinx.serialization.Serializable

@Serializable
enum class OrganizationContactType {
    PRIMARY,
    SECONDARY,
    TECHNICAL,
}

@Serializable
data class OrganizationContact(
    val firstName: String,
    val lastName: String,
    val email: String,
    val title: String,
    val phoneNumber: String? = null,
    val type: OrganizationContactType
)
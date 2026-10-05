package bosca.profile.organization.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

enum class OrganizationSignupGroupType {
    ADMINISTRATORS,
    USERS,
    UNKNOWN
}

@Serializable
data class OrganizationSignupEmailInput(
    val email: String,
    val type: OrganizationSignupGroupType
) {

    fun toOrganizationSignupEmail(organizationId: UUID, groupId: UUID): OrganizationSignupEmail {
        return OrganizationSignupEmail(
            email = email,
            organizationId = organizationId,
            groupId = groupId,
            created = java.time.OffsetDateTime.now(),
            expires = java.time.OffsetDateTime.now().plusDays(60)
        )
    }
}
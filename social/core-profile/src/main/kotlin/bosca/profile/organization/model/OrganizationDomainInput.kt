package bosca.profile.organization.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class OrganizationDomainInput(
    val domain: String,
    val autoJoin: Boolean,
    val defaultGroupId: UUID? = null,
    val type: OrganizationSignupGroupType? = null
) {

    fun toDomain(id: UUID) = OrganizationDomain(
        organizationId = id,
        domain = domain.lowercase(),
        autoJoin = autoJoin,
        groupId = defaultGroupId
    )
}
package bosca.profile.organization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.organization.model.OrganizationDomain
import bosca.profile.organization.model.OrganizationSignupGroupType
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.SecurityService
import bosca.serialization.UUID

@TypeController
class OrganizationDomainController(
    private val securityService: SecurityService
) : GraphQLController<OrganizationDomain> {

    @Field
    fun domain(domain: OrganizationDomain) = domain.domain

    @Field
    fun autoJoin(domain: OrganizationDomain) = domain.autoJoin

    @Field
    suspend fun type(domain: OrganizationDomain): OrganizationSignupGroupType {
        val group = securityService.getGroupById(domain.groupId ?: return OrganizationSignupGroupType.UNKNOWN)
        return if (group.name.endsWith(".admins")) OrganizationSignupGroupType.ADMINISTRATORS else OrganizationSignupGroupType.USERS
    }

    @Field
    suspend fun group(domain: OrganizationDomain) = domain.groupId?.let { securityService.getGroupById(it) } ?: Group(
        id = UUID.NIL, name = "Unknown", description = "Unknown Group", type = GroupType.SYSTEM
    )
}

package bosca.profile.organization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.organization.model.OrganizationSignupGroupType
import bosca.profile.organization.model.OrganizationSignupToken
import bosca.security.service.SecurityService

@TypeController
class OrganizationSignupTokenController(
    private val securityService: SecurityService
) : GraphQLController<OrganizationSignupToken> {

    @Field
    suspend fun type(token: OrganizationSignupToken): OrganizationSignupGroupType {
        val group = securityService.getGroupById(token.groupId ?: error("No group id"))
        return if (group.name.endsWith(".admins")) OrganizationSignupGroupType.ADMINISTRATORS else OrganizationSignupGroupType.USERS
    }

    @Field
    fun token(token: OrganizationSignupToken) = token.token

    @Field
    suspend fun group(token: OrganizationSignupToken) = securityService.getGroupById(token.groupId ?: error("No group id"))
}

package bosca.profile.organization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.organization.model.OrganizationSignupEmail
import bosca.profile.organization.model.OrganizationSignupGroupType
import bosca.security.service.SecurityService

@TypeController
class OrganizationSignupEmailController(
    private val securityService: SecurityService
) : GraphQLController<OrganizationSignupEmail> {

    @Field
    fun email(email: OrganizationSignupEmail) = email.email

    @Field
    suspend fun type(email: OrganizationSignupEmail): OrganizationSignupGroupType {
        val group = securityService.getGroupById(email.groupId ?: error("No group id"))
        return if (group.name.endsWith(".admins")) OrganizationSignupGroupType.ADMINISTRATORS else OrganizationSignupGroupType.USERS
    }

    @Field
    suspend fun group(email: OrganizationSignupEmail) = securityService.getGroupById(email.groupId ?: error("No group id"))
}

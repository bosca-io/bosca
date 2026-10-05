package bosca.profile.organization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.organization.model.OrganizationPermission
import bosca.profile.organization.model.OrganizationSignupGroupType
import bosca.security.service.SecurityService

@TypeController
class OrganizationPermissionController(
    private val securityService: SecurityService
) : GraphQLController<OrganizationPermission> {

    @Field
    suspend fun group(permission: OrganizationPermission) = securityService.getGroupById(permission.groupId)

    @Field
    suspend fun groupType(permission: OrganizationPermission): OrganizationSignupGroupType {
        val group = securityService.getGroupById(permission.groupId)
        if (group.name.endsWith(".admins")) return OrganizationSignupGroupType.ADMINISTRATORS
        return OrganizationSignupGroupType.USERS
    }

    @Field
    fun action(permission: OrganizationPermission) = permission.action
}
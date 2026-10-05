package bosca.configuration.graphql

import bosca.configuration.model.ConfigurationPermission
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService

@TypeController
class ConfigurationPermissionController(
    private val securityService: SecurityService
) : GraphQLController<ConfigurationPermission> {

    @Field
    fun action(permission: ConfigurationPermission): PermissionAction = permission.action

    @Field
    suspend fun group(permission: ConfigurationPermission) = securityService.getGroupById(permission.groupId)
}
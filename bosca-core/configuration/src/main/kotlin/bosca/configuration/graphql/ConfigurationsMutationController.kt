package bosca.configuration.graphql

import bosca.configuration.model.Configuration
import bosca.configuration.model.ConfigurationInput
import bosca.configuration.security.ConfigurationPermissionEvaluator
import bosca.configuration.service.ConfigurationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object ConfigurationsMutation

@TypeController
class ConfigurationsMutationController(
    private val service: ConfigurationService,
    private val permissionEvaluator: ConfigurationPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<ConfigurationsMutation> {

    /** Saves values with EDIT; changing visibility or permissions also requires MANAGE. */
    @Field
    suspend fun setConfiguration(
        authentication: AuthenticationContext,
        configuration: ConfigurationInput
    ): Configuration {
        val current = service.getByKey(configuration.key)
        if (current == null) {
            groupEvaluator.verifyHasSaGroup(authentication)
        } else {
            permissionEvaluator.verifyAllowed(authentication, current, PermissionAction.EDIT)
            val requestedPermissions = configuration.permissions?.map { it.action to it.groupId }?.toSet()
            val permissionsChanged = requestedPermissions != null &&
                requestedPermissions != service.getPermissions(current).map { it.action to it.groupId }.toSet()
            if (current.public != configuration.public || permissionsChanged) {
                permissionEvaluator.verifyAllowed(authentication, current, PermissionAction.MANAGE)
            }
        }
        return service.setConfiguration(configuration)
    }

    @Field
    suspend fun deleteConfiguration(authentication: AuthenticationContext, key: String): UUID {
        val current = service.getByKey(key) ?: throw NoSuchElementException()
        permissionEvaluator.verifyAllowed(authentication, current, PermissionAction.MANAGE)
        return service.deleteConfiguration(key)
    }
}

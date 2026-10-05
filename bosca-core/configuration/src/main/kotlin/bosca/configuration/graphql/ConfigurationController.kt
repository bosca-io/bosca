package bosca.configuration.graphql

import bosca.configuration.model.Configuration
import bosca.configuration.security.ConfigurationPermissionEvaluator
import bosca.configuration.service.ConfigurationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.json.JsonElement

@TypeController
class ConfigurationController(
    private val service: ConfigurationService,
    private val permissionEvaluator: ConfigurationPermissionEvaluator
) : GraphQLController<Configuration> {

    @Field
    fun id(configuration: Configuration) = configuration.id

    @Field
    fun key(configuration: Configuration) = configuration.key

    @Field
    fun description(configuration: Configuration) = configuration.description

    @Field
    suspend fun value(authentication: AuthenticationContext?, configuration: Configuration): JsonElement? {
        permissionEvaluator.verifyAllowed(authentication, configuration, PermissionAction.VIEW)
        return service.getValue(configuration.id)
    }

    @Field
    fun public(configuration: Configuration) = configuration.public

    @Field
    suspend fun permissions(
        authentication: AuthenticationContext,
        configuration: Configuration
    ): List<EntityPermission> {
        permissionEvaluator.verifyAllowed(authentication, configuration, PermissionAction.MANAGE)
        return service.getPermissions(configuration)
    }
}
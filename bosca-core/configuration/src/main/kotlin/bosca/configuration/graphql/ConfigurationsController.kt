package bosca.configuration.graphql

import bosca.configuration.model.Configuration
import bosca.configuration.security.ConfigurationPermissionEvaluator
import bosca.configuration.service.ConfigurationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

object Configurations

@TypeController
class ConfigurationsController(
    private val service: ConfigurationService,
    private val permissionEvaluator: ConfigurationPermissionEvaluator
) : GraphQLController<Configurations> {

    @Field
    suspend fun all(authentication: AuthenticationContext?): List<Configuration> {
        val configurations = service.getAll()
        return configurations.filter {
            permissionEvaluator.isAllowed(
                authentication,
                it,
                PermissionAction.LIST
            )
        }
    }

    @Field
    suspend fun configuration(authentication: AuthenticationContext?, key: String): Configuration? {
        val configuration = service.getByKey(key) ?: return null
        permissionEvaluator.verifyAllowed(authentication, configuration, PermissionAction.VIEW)
        return configuration
    }
}
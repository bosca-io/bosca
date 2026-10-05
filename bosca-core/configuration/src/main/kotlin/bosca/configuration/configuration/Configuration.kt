package bosca.configuration.configuration

import bosca.configuration.security.ConfigurationPermissionEvaluator
import bosca.configuration.service.ConfigurationService
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService

@Providers
class Configuration {

    @Provider(singleton = true)
    fun permissionEvaluator(
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator,
        service: ConfigurationService,
    ) = ConfigurationPermissionEvaluator(
        securityService,
        groupEvaluator,
        service,
    )
}
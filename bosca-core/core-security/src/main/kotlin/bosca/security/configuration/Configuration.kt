package bosca.security.configuration

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService

@Providers
class CoreConfiguration {

    @Provider(singleton = true)
    fun groupEvaluator(service: SecurityService): GroupEvaluator = GroupEvaluator(service)
}
package bosca.gateway.configuration

import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.gateway.migration.GatewayMigration
import bosca.gateway.service.GatewayPermissionEvaluator
import bosca.gateway.service.GatewayService
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication

@Providers
class Configuration {

    @Provider(name = "gateway-migrations")
    fun migration(): Migration = GatewayMigration()

    @Provider(singleton = true)
    fun gatewayPermissionEvaluator(
        service: GatewayService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator,
    ) = GatewayPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun gatewayProxyConfig(application: BoscaApplication): GatewayProxyConfig =
        GatewayProxyConfig.load(application)
}

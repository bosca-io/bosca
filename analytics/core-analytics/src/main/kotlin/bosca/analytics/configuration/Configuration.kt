package bosca.analytics.configuration

import bosca.analytics.security.AnalyticsDashboardPermissionEvaluator
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.analytics.service.AnalyticsDashboardService
import bosca.analytics.service.AnalyticsQueryService
import bosca.analytics.service.AnalyticsVisualizationService
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService

@Providers
class Configuration {

    @Provider(singleton = true)
    fun queryPermissionEvaluator(
        service: AnalyticsQueryService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ) = AnalyticsQueryPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun visualizationPermissionEvaluator(
        service: AnalyticsVisualizationService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ) = AnalyticsVisualizationPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun dashboardPermissionEvaluator(
        service: AnalyticsDashboardService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ) = AnalyticsDashboardPermissionEvaluator(service, securityService, groupEvaluator)
}
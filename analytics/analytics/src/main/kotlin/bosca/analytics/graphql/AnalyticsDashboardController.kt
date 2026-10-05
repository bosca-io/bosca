package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsDashboard
import bosca.analytics.model.AnalyticsDashboardParameter
import bosca.analytics.model.AnalyticsVisualizationInstance
import bosca.analytics.security.AnalyticsDashboardPermissionEvaluator
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.analytics.service.AnalyticsDashboardService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

@TypeController
class AnalyticsDashboardController(
    private val dashboardService: AnalyticsDashboardService,
    private val dashboardPermissionEvaluator: AnalyticsDashboardPermissionEvaluator,
    private val visualizationPermissionEvaluator: AnalyticsVisualizationPermissionEvaluator
) : GraphQLController<AnalyticsDashboard> {

    @Field
    fun id(dashboard: AnalyticsDashboard) = dashboard.id

    @Field
    fun key(dashboard: AnalyticsDashboard) = dashboard.key

    @Field
    fun name(dashboard: AnalyticsDashboard) = dashboard.name

    @Field
    fun description(dashboard: AnalyticsDashboard) = dashboard.description

    @Field
    fun configuration(dashboard: AnalyticsDashboard) = dashboard.configuration

    @Field
    fun parameters(dashboard: AnalyticsDashboard): List<AnalyticsDashboardParameter> {
        val json = dashboard.parameters ?: return emptyList()
        return Json.decodeFromJsonElement<List<AnalyticsDashboardParameter>>(json)
    }

    @Field
    suspend fun permissions(authentication: AuthenticationContext?, dashboard: AnalyticsDashboard): List<Permission> {
        if (!dashboardPermissionEvaluator.isAllowed(authentication, dashboard, PermissionAction.MANAGE)) {
            return emptyList()
        }
        return dashboardService.getPermissions(dashboard)
            .map { Permission(it.groupId, it.action) }
    }

    @Field
    suspend fun visualizations(authentication: AuthenticationContext, dashboard: AnalyticsDashboard): List<AnalyticsVisualizationInstance> {
        val visualizations = dashboardService.getVisualizations(dashboard.id)
        return visualizations.filter {
            visualizationPermissionEvaluator.isAllowed(authentication, it.visualization, PermissionAction.VIEW)
        }
    }
}

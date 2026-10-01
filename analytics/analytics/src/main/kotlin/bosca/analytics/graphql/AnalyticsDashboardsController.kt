package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsDashboard
import bosca.analytics.security.AnalyticsDashboardPermissionEvaluator
import bosca.analytics.service.AnalyticsDashboardService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

object AnalyticsDashboards

@TypeController
class AnalyticsDashboardsController(
    private val dashboardService: AnalyticsDashboardService,
    private val permissionEvaluator: AnalyticsDashboardPermissionEvaluator
) : GraphQLController<AnalyticsDashboards> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<AnalyticsDashboard> {
        return dashboardService.getDashboards(0, Int.MAX_VALUE)
            .filter {
                permissionEvaluator.isAllowed(authentication, it, PermissionAction.VIEW)
            }
    }

    @Field
    suspend fun byId(authentication: AuthenticationContext, id: UUID): AnalyticsDashboard {
        val dashboard = dashboardService.getDashboardById(id)
        permissionEvaluator.verifyAllowed(authentication, dashboard, PermissionAction.VIEW)
        return dashboard
    }

    @Field
    suspend fun byKey(authentication: AuthenticationContext, key: String): AnalyticsDashboard? {
        val dashboard = dashboardService.getDashboardByKey(key) ?: return null
        permissionEvaluator.verifyAllowed(authentication, dashboard, PermissionAction.VIEW)
        return dashboard
    }
}

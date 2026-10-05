package bosca.ai.kit.agents.analytics

import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.analytics.security.AnalyticsDashboardPermissionEvaluator
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.analytics.service.AnalyticsDashboardService
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
import bosca.analytics.service.AnalyticsVisualizationService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import kotlinx.serialization.json.Json

/** Platform analytics services and boundary authorization used by Kit's analytics tools. */
class AnalyticsServices(
    val queryService: AnalyticsQueryService,
    val queryExecutionService: AnalyticsQueryExecutionService,
    val visualizationService: AnalyticsVisualizationService,
    val dashboardService: AnalyticsDashboardService,
    val queryPermissionEvaluator: AnalyticsQueryPermissionEvaluator,
    val visualizationPermissionEvaluator: AnalyticsVisualizationPermissionEvaluator,
    val dashboardPermissionEvaluator: AnalyticsDashboardPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
    val json: Json,
) {
    /** Query and visualization creation follows the analytics GraphQL boundary's manager policy. */
    fun verifyCanManageAnalytics(authentication: AuthenticationContext) {
        if (!groupEvaluator.hasGroup(authentication, ANALYTICS_MANAGER_GROUP) &&
            !groupEvaluator.hasAdminGroup(authentication)
        ) {
            groupEvaluator.throwUnauthorized()
        }
    }

    /** Dashboard creation/composition follows the dashboard boundary's editor-or-manager policy. */
    fun verifyCanManageDashboards(authentication: AuthenticationContext) {
        if (!groupEvaluator.hasGroup(authentication, ANALYTICS_MANAGER_GROUP) &&
            !groupEvaluator.hasEditorGroup(authentication)
        ) {
            groupEvaluator.throwUnauthorized()
        }
    }
}

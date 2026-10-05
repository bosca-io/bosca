package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.analytics.service.AnalyticsVisualizationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

object AnalyticsVisualizations

@TypeController
class AnalyticsVisualizationsController(
    private val visualizationService: AnalyticsVisualizationService,
    private val permissionEvaluator: AnalyticsVisualizationPermissionEvaluator
) : GraphQLController<AnalyticsVisualizations> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<AnalyticsVisualization> {
        return visualizationService.getVisualizations(0, Int.MAX_VALUE)
            .filter {
                permissionEvaluator.isAllowed(authentication, it, PermissionAction.VIEW)
            }
    }

    @Field
    suspend fun byId(authentication: AuthenticationContext, id: UUID): AnalyticsVisualization {
        val visualization = visualizationService.getVisualizationById(id)
        permissionEvaluator.verifyAllowed(authentication, visualization, PermissionAction.VIEW)
        return visualization
    }

    @Field
    suspend fun byKey(authentication: AuthenticationContext, key: String): AnalyticsVisualization? {
        val visualization = visualizationService.getVisualizationByKey(key) ?: return null
        permissionEvaluator.verifyAllowed(authentication, visualization, PermissionAction.VIEW)
        return visualization
    }
}

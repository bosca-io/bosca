package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.analytics.service.AnalyticsVisualizationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class AnalyticsVisualizationController(
    private val visualizationService: AnalyticsVisualizationService,
    private val permissionEvaluator: AnalyticsVisualizationPermissionEvaluator
) : GraphQLController<AnalyticsVisualization> {

    @Field
    fun id(visualization: AnalyticsVisualization) = visualization.id

    @Field
    fun key(visualization: AnalyticsVisualization) = visualization.key

    @Field
    fun name(visualization: AnalyticsVisualization) = visualization.name

    @Field
    fun description(visualization: AnalyticsVisualization) = visualization.description

    @Field
    fun queryId(visualization: AnalyticsVisualization) = visualization.queryId

    @Field
    fun type(visualization: AnalyticsVisualization) = visualization.type

    @Field
    fun configuration(visualization: AnalyticsVisualization) = visualization.configuration

    @Field
    suspend fun permissions(authentication: AuthenticationContext?, visualization: AnalyticsVisualization): List<Permission> {
        if (!permissionEvaluator.isAllowed(authentication, visualization, PermissionAction.MANAGE)) {
            return emptyList()
        }
        return visualizationService.getPermissions(visualization)
            .map { Permission(it.groupId, it.action) }
    }
}
package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationInput
import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.analytics.service.AnalyticsVisualizationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object AnalyticsVisualizationsMutation

@TypeController
class AnalyticsVisualizationsMutationController(
    private val visualizationService: AnalyticsVisualizationService,
    private val groupEvaluator: GroupEvaluator,
    private val permissionEvaluator: AnalyticsVisualizationPermissionEvaluator
) : GraphQLController<AnalyticsVisualizationsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, visualization: AnalyticsVisualizationInput): AnalyticsVisualization {
        verifyCanManage(authentication)
        return visualizationService.addVisualization(visualization)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, visualization: AnalyticsVisualizationInput): AnalyticsVisualization {
        verifyCanManage(authentication)
        return visualizationService.editVisualization(visualization)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        verifyCanManage(authentication)
        visualizationService.deleteVisualizationById(id)
        return true
    }

    @Field
    suspend fun addPermission(
        authentication: AuthenticationContext,
        permission: PermissionInput
    ): Permission {
        val visualization = visualizationService.getVisualizationById(permission.entityId)
        permissionEvaluator.verifyAllowed(authentication, visualization, PermissionAction.MANAGE)
        visualizationService.addPermission(permission)
        return Permission(
            groupId = permission.groupId,
            action = permission.action
        )
    }

    @Field
    suspend fun deletePermission(
        authentication: AuthenticationContext,
        permission: PermissionInput
    ): Permission {
        val visualization = visualizationService.getVisualizationById(permission.entityId)
        permissionEvaluator.verifyAllowed(authentication, visualization, PermissionAction.MANAGE)
        visualizationService.deletePermission(permission)
        return Permission(
            groupId = permission.groupId,
            action = permission.action
        )
    }

    private fun verifyCanManage(authentication: AuthenticationContext) {
        val canManage = groupEvaluator.hasGroup(authentication, ANALYTICS_MANAGER_GROUP) ||
            groupEvaluator.hasAdminGroup(authentication)
        if (!canManage) {
            groupEvaluator.throwUnauthorized()
        }
    }
}

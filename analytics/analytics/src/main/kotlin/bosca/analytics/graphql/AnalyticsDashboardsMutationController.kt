package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsDashboard
import bosca.analytics.model.AnalyticsDashboardInput
import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.analytics.security.AnalyticsDashboardPermissionEvaluator
import bosca.analytics.service.AnalyticsDashboardService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

object AnalyticsDashboardsMutation

@TypeController
class AnalyticsDashboardsMutationController(
    private val dashboardService: AnalyticsDashboardService,
    private val groupEvaluator: GroupEvaluator,
    private val permissionEvaluator: AnalyticsDashboardPermissionEvaluator
) : GraphQLController<AnalyticsDashboardsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, dashboard: AnalyticsDashboardInput): AnalyticsDashboard {
        verifyCanManage(authentication)
        return dashboardService.addDashboard(dashboard)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, dashboard: AnalyticsDashboardInput): AnalyticsDashboard {
        verifyCanManage(authentication)
        return dashboardService.editDashboard(dashboard)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        verifyCanManage(authentication)
        dashboardService.deleteDashboardById(id)
        return true
    }

    @Field
    suspend fun addPermission(
        authentication: AuthenticationContext,
        permission: PermissionInput
    ): Permission {
        val dashboard = dashboardService.getDashboardById(permission.entityId)
        permissionEvaluator.verifyAllowed(authentication, dashboard, PermissionAction.MANAGE)
        dashboardService.addPermission(permission)
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
        val dashboard = dashboardService.getDashboardById(permission.entityId)
        permissionEvaluator.verifyAllowed(authentication, dashboard, PermissionAction.MANAGE)
        dashboardService.deletePermission(permission)
        return Permission(
            groupId = permission.groupId,
            action = permission.action
        )
    }

    @Field
    suspend fun addVisualization(authentication: AuthenticationContext, dashboardId: UUID, visualizationId: UUID, configuration: JsonElement): UUID {
        verifyCanManage(authentication)
        return dashboardService.addVisualization(dashboardId, visualizationId, configuration)
    }

    @Field
    suspend fun removeVisualization(authentication: AuthenticationContext, id: UUID): Boolean {
        verifyCanManage(authentication)
        dashboardService.removeVisualization(id)
        return true
    }

    // Dashboards historically accept the editor role; analytics.manager is
    // additionally accepted so analytics managers can administer every
    // analytics entity without holding a content role.
    private fun verifyCanManage(authentication: AuthenticationContext) {
        val canManage = groupEvaluator.hasGroup(authentication, ANALYTICS_MANAGER_GROUP) ||
            groupEvaluator.hasEditorGroup(authentication)
        if (!canManage) {
            groupEvaluator.throwUnauthorized()
        }
    }
}

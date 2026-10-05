package bosca.analytics.service

import bosca.analytics.model.AnalyticsDashboard
import bosca.analytics.model.AnalyticsDashboardInput
import bosca.analytics.model.AnalyticsVisualizationInstance
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionInput
import bosca.security.model.PermissionService
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing analytics dashboards, including CRUD operations on dashboards,
 * associating visualizations with dashboards, and controlling access permissions.
 *
 * Extends [PermissionService] to provide permission evaluation and batch-loading
 * capabilities for [AnalyticsDashboard] entities identified by [UUID].
 */
interface AnalyticsDashboardService : PermissionService<AnalyticsDashboard, UUID> {

    /**
     * Retrieves a paginated list of analytics dashboards.
     *
     * @param offset the zero-based index of the first dashboard to return
     * @param limit the maximum number of dashboards to return
     * @return a list of [AnalyticsDashboard] instances within the requested page
     */
    suspend fun getDashboards(offset: Long, limit: Int): List<AnalyticsDashboard>

    /**
     * Retrieves a single analytics dashboard by its unique identifier.
     *
     * @param id the unique identifier of the dashboard
     * @return the matching [AnalyticsDashboard]
     * @throws Exception if no dashboard exists with the given [id]
     */
    suspend fun getDashboardById(id: UUID): AnalyticsDashboard

    /**
     * Retrieves a single analytics dashboard by its human-readable key.
     *
     * @param key the unique key string identifying the dashboard
     * @return the matching [AnalyticsDashboard], or `null` if no dashboard has the given key
     */
    suspend fun getDashboardByKey(key: String): AnalyticsDashboard?

    /**
     * Creates a new analytics dashboard from the provided input.
     *
     * @param dashboard the input data describing the dashboard to create
     * @return the newly created [AnalyticsDashboard] with its assigned identifier
     */
    suspend fun addDashboard(dashboard: AnalyticsDashboardInput): AnalyticsDashboard

    /**
     * Updates an existing analytics dashboard with the provided input data.
     * The dashboard to update is identified by the [AnalyticsDashboardInput.id] field.
     *
     * @param dashboard the input data containing the updated dashboard fields
     * @return the updated [AnalyticsDashboard]
     */
    suspend fun editDashboard(dashboard: AnalyticsDashboardInput): AnalyticsDashboard

    /**
     * Deletes an analytics dashboard by its unique identifier.
     *
     * @param id the unique identifier of the dashboard to delete
     */
    suspend fun deleteDashboardById(id: UUID)

    /**
     * Associates a visualization with a dashboard, using the provided configuration
     * to control how the visualization is rendered within the dashboard.
     *
     * @param dashboardId the unique identifier of the target dashboard
     * @param visualizationId the unique identifier of the visualization to add
     * @param configuration JSON configuration controlling the visualization's placement and behavior
     * @return the unique identifier of the newly created visualization instance association
     */
    suspend fun addVisualization(dashboardId: UUID, visualizationId: UUID, configuration: JsonElement): UUID

    /**
     * Removes a visualization instance association from a dashboard.
     *
     * @param id the unique identifier of the visualization instance to remove
     */
    suspend fun removeVisualization(id: UUID)

    /**
     * Retrieves all visualization instances associated with a given dashboard.
     * Each instance pairs a visualization definition with its dashboard-specific configuration.
     *
     * @param dashboardId the unique identifier of the dashboard
     * @return a list of [AnalyticsVisualizationInstance] entries linked to the dashboard
     */
    suspend fun getVisualizations(dashboardId: UUID): List<AnalyticsVisualizationInstance>

    /**
     * Grants a permission on a dashboard to a subject (e.g., user or group)
     * as described by the given [PermissionInput].
     *
     * @param permission the permission specification identifying the entity, subject, and access level
     * @return the created [EntityPermission] record
     */
    suspend fun addPermission(permission: PermissionInput): EntityPermission

    /**
     * Revokes a permission on a dashboard from a subject as described by the given [PermissionInput].
     *
     * @param permission the permission specification identifying the entity, subject, and access level to revoke
     * @return the removed [EntityPermission] record
     */
    suspend fun deletePermission(permission: PermissionInput): EntityPermission
}

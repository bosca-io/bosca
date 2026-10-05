package bosca.analytics.service

import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationInput
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionInput
import bosca.security.model.PermissionService
import bosca.serialization.UUID

/**
 * Service for managing analytics visualization definitions, which describe how query results
 * are rendered (e.g., as charts, tables, or other visual components).
 * Provides CRUD operations on visualizations and access permission control.
 *
 * Each visualization has a designated type (e.g., bar chart, table, pie chart) and may
 * optionally reference an analytics query whose results it renders.
 *
 * Extends [PermissionService] to provide permission evaluation and batch-loading
 * capabilities for [AnalyticsVisualization] entities identified by [UUID].
 */
interface AnalyticsVisualizationService : PermissionService<AnalyticsVisualization, UUID> {

    /**
     * Retrieves a paginated list of analytics visualization definitions.
     *
     * @param offset the zero-based index of the first visualization to return
     * @param limit the maximum number of visualizations to return
     * @return a list of [AnalyticsVisualization] instances within the requested page
     */
    suspend fun getVisualizations(offset: Long, limit: Int): List<AnalyticsVisualization>

    /**
     * Retrieves a single analytics visualization by its unique identifier.
     *
     * @param id the unique identifier of the visualization
     * @return the matching [AnalyticsVisualization]
     * @throws Exception if no visualization exists with the given [id]
     */
    suspend fun getVisualizationById(id: UUID): AnalyticsVisualization

    /**
     * Retrieves a single analytics visualization by its human-readable key.
     *
     * @param key the unique key string identifying the visualization
     * @return the matching [AnalyticsVisualization], or `null` if no visualization has the given key
     */
    suspend fun getVisualizationByKey(key: String): AnalyticsVisualization?

    /**
     * Creates a new analytics visualization definition from the provided input.
     *
     * @param visualization the input data describing the visualization to create, including
     *   its type, optional query reference, and rendering configuration
     * @return the newly created [AnalyticsVisualization] with its assigned identifier
     */
    suspend fun addVisualization(visualization: AnalyticsVisualizationInput): AnalyticsVisualization

    /**
     * Updates an existing analytics visualization definition with the provided input data.
     * The visualization to update is identified by the [AnalyticsVisualizationInput.id] field.
     *
     * @param visualization the input data containing the updated visualization fields
     * @return the updated [AnalyticsVisualization]
     */
    suspend fun editVisualization(visualization: AnalyticsVisualizationInput): AnalyticsVisualization

    /**
     * Deletes an analytics visualization definition by its unique identifier.
     *
     * @param id the unique identifier of the visualization to delete
     */
    suspend fun deleteVisualizationById(id: UUID)

    /**
     * Grants a permission on a visualization to a subject (e.g., user or group)
     * as described by the given [PermissionInput].
     *
     * @param permission the permission specification identifying the entity, subject, and access level
     * @return the created [EntityPermission] record
     */
    suspend fun addPermission(permission: PermissionInput): EntityPermission

    /**
     * Revokes a permission on a visualization from a subject as described by the given [PermissionInput].
     *
     * @param permission the permission specification identifying the entity, subject, and access level to revoke
     * @return the removed [EntityPermission] record
     */
    suspend fun deletePermission(permission: PermissionInput): EntityPermission
}

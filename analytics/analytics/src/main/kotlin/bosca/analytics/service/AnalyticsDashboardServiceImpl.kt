package bosca.analytics.service

import bosca.analytics.model.AnalyticsDashboard
import bosca.analytics.model.AnalyticsDashboardInput
import bosca.analytics.model.AnalyticsDashboardPermission
import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationInstance
import bosca.analytics.repository.AnalyticsDashboardPermissionRepository
import bosca.analytics.repository.AnalyticsDashboardRepository
import bosca.analytics.repository.AnalyticsDashboardVisualizationRepository
import bosca.analytics.repository.AnalyticsVisualizationRepository
import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionInput
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@ServiceImplementation
class AnalyticsDashboardServiceImpl(
    private val dashboardRepository: AnalyticsDashboardRepository,
    private val permissionRepository: AnalyticsDashboardPermissionRepository,
    private val dashboardVisualizationRepository: AnalyticsDashboardVisualizationRepository,
    private val visualizationRepository: AnalyticsVisualizationRepository
) : AnalyticsDashboardService {

    private val permissions = ServiceCache<UUID, List<EntityPermission>>(
        "analytics:dashboard:permissions",
        UUIDKeySerializer,
        { keys, batch ->
            val permissionsByGroupId = permissionRepository.getPermissionsByIds(keys).groupBy { it.entityId }
            keys.forEach {
                batch.setData(it, permissionsByGroupId[it] ?: return@forEach)
            }
        }
    ) {
        permissionRepository.getPermissionsById(it)
    }

    override suspend fun getDashboards(offset: Long, limit: Int): List<AnalyticsDashboard> =
        dashboardRepository.getAll(offset, limit)

    override suspend fun getDashboardById(id: UUID): AnalyticsDashboard =
        dashboardRepository.getById(id) ?: error("Analytics Dashboard not found: $id")

    override suspend fun getDashboardByKey(key: String): AnalyticsDashboard? =
        dashboardRepository.getByKey(key)

    override suspend fun addDashboard(dashboard: AnalyticsDashboardInput): AnalyticsDashboard = transaction {
        val newDashboard = dashboardRepository.add(AnalyticsDashboard(
            key = dashboard.key,
            name = dashboard.name,
            description = dashboard.description,
            configuration = dashboard.configuration,
            parameters = Json.encodeToJsonElement(dashboard.parameters)
        ))
        dashboard.visualizations.forEach {
            dashboardVisualizationRepository.add(UUID.random(), newDashboard.id, it.visualizationId, it.configuration)
        }
        newDashboard
    }

    override suspend fun editDashboard(dashboard: AnalyticsDashboardInput): AnalyticsDashboard = transaction {
        val existing = dashboardRepository.getById(dashboard.id)
            ?: error("Analytics Dashboard not found: ${dashboard.id}")
        val updated = existing.copy(
            key = dashboard.key,
            name = dashboard.name,
            description = dashboard.description,
            configuration = dashboard.configuration,
            parameters = Json.encodeToJsonElement(dashboard.parameters)
        )
        dashboardRepository.edit(updated)
        dashboardVisualizationRepository.removeAll(updated.id)
        dashboard.visualizations.forEach {
            dashboardVisualizationRepository.add(UUID.random(), updated.id, it.visualizationId, it.configuration)
        }
        updated
    }

    override suspend fun deleteDashboardById(id: UUID) {
        dashboardVisualizationRepository.removeAll(id)
        dashboardRepository.deleteById(id)
    }

    override suspend fun addVisualization(dashboardId: UUID, visualizationId: UUID, configuration: JsonElement): UUID {
        val id = UUID.random()
        dashboardVisualizationRepository.add(id, dashboardId, visualizationId, configuration)
        return id
    }

    override suspend fun removeVisualization(id: UUID) {
        dashboardVisualizationRepository.remove(id)
    }

    override suspend fun getVisualizations(dashboardId: UUID): List<AnalyticsVisualizationInstance> {
        val links = dashboardVisualizationRepository.getVisualizationsByDashboardId(dashboardId)
        if (links.isEmpty()) return emptyList()

        val ids = links.map { it.visualizationId }
        val visualizations = visualizationRepository.getByIds(ids).associateBy { it.id }

        return links.mapNotNull { link ->
            val visualization = visualizations[link.visualizationId] ?: return@mapNotNull null
            AnalyticsVisualizationInstance(
                id = link.id,
                configuration = link.configuration,
                visualization = visualization
            )
        }
    }

    override suspend fun getPermissions(entity: AnalyticsDashboard): List<EntityPermission> {
        return permissions.get(entity.id) ?: emptyList()
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        permissions.addToBatch(batch)
    }

    private suspend fun removeFromCache(id: UUID) {
        permissions.remove(id)
    }

    override suspend fun addPermission(permission: PermissionInput): EntityPermission {
        permissionRepository.addPermission(permission.entityId, permission.groupId, permission.action)
        removeFromCache(permission.entityId)
        return AnalyticsDashboardPermission(
            entityId = permission.entityId,
            groupId = permission.groupId,
            action = permission.action,
        )
    }

    override suspend fun deletePermission(permission: PermissionInput): EntityPermission {
        permissionRepository.deletePermission(permission.entityId, permission.groupId, permission.action)
        removeFromCache(permission.entityId)
        return AnalyticsDashboardPermission(
            entityId = permission.entityId,
            groupId = permission.groupId,
            action = permission.action,
        )
    }
}

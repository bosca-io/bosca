package bosca.analytics.service

import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationInput
import bosca.analytics.model.AnalyticsVisualizationPermission
import bosca.analytics.repository.AnalyticsVisualizationPermissionRepository
import bosca.analytics.repository.AnalyticsVisualizationRepository
import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionInput
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class AnalyticsVisualizationServiceImpl(
    private val visualizationRepository: AnalyticsVisualizationRepository,
    private val permissionRepository: AnalyticsVisualizationPermissionRepository
) : AnalyticsVisualizationService {

    private val permissions = ServiceCache<UUID, List<EntityPermission>>(
        "analytics:visualization:permissions",
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

    override suspend fun getVisualizations(offset: Long, limit: Int): List<AnalyticsVisualization> =
        visualizationRepository.getAll(offset, limit)

    override suspend fun getVisualizationById(id: UUID): AnalyticsVisualization =
        visualizationRepository.getById(id) ?: error("Analytics Visualization not found: $id")

    override suspend fun getVisualizationByKey(key: String): AnalyticsVisualization? =
        visualizationRepository.getByKey(key)

    override suspend fun addVisualization(visualization: AnalyticsVisualizationInput): AnalyticsVisualization = transaction {
        val newVisualization = AnalyticsVisualization(
            key = visualization.key,
            name = visualization.name,
            description = visualization.description,
            queryId = visualization.queryId,
            type = visualization.type,
            configuration = visualization.configuration
        )
        visualizationRepository.add(newVisualization)
    }

    override suspend fun editVisualization(visualization: AnalyticsVisualizationInput): AnalyticsVisualization = transaction {
        val existing = visualizationRepository.getById(visualization.id)
            ?: error("Analytics Visualization not found: ${visualization.id}")
        
        val updated = existing.copy(
            key = visualization.key,
            name = visualization.name,
            description = visualization.description,
            queryId = visualization.queryId,
            type = visualization.type,
            configuration = visualization.configuration
        )
        visualizationRepository.edit(updated)
        updated
    }

    override suspend fun deleteVisualizationById(id: UUID) {
        visualizationRepository.deleteById(id)
    }

    override suspend fun getPermissions(entity: AnalyticsVisualization): List<EntityPermission> {
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
        return AnalyticsVisualizationPermission(
            entityId = permission.entityId,
            groupId = permission.groupId,
            action = permission.action,
        )
    }

    override suspend fun deletePermission(permission: PermissionInput): EntityPermission {
        permissionRepository.deletePermission(permission.entityId, permission.groupId, permission.action)
        removeFromCache(permission.entityId)
        return AnalyticsVisualizationPermission(
            entityId = permission.entityId,
            groupId = permission.groupId,
            action = permission.action,
        )
    }
}
